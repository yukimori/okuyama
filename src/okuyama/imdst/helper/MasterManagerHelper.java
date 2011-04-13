package okuyama.imdst.helper;

import java.io.*;
import java.util.*;
import java.net.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import okuyama.base.lang.BatchException;
import okuyama.base.job.AbstractHelper;
import okuyama.base.job.IJob;
import okuyama.base.util.ILogger;
import okuyama.base.util.LoggerFactory;
import okuyama.imdst.util.KeyMapManager;
import okuyama.imdst.util.ImdstDefine;
import okuyama.imdst.util.DataDispatcher;
import okuyama.imdst.util.StatusUtil;
import okuyama.imdst.util.protocol.*;
import okuyama.imdst.util.io.KeyNodeConnector;
import okuyama.imdst.util.JavaSystemApi;
import okuyama.imdst.util.SystemUtil;
import okuyama.imdst.util.io.CustomReader;

import com.sun.mail.util.BASE64DecoderStream;
import com.sun.mail.util.BASE64EncoderStream;


/**
 * MasterNodeのメイン実行部分<br>
 *
 * @author T.Okuyama
 * @license GPL(Lv3)
 */
public class MasterManagerHelper extends AbstractMasterManagerHelper {


    // プロトコルモード
    private String protocolMode = null;
    private IProtocolTaker porotocolTaker = null;
    private boolean isProtocolOkuyama = true;

    private String myPollQueue = "";

    // DataNode逆アクセス指定(アクセスバランシング)
    private boolean reverseAccess = false;

    // 一貫性モード
    // 0=弱一貫性(デフォルト)
    // 1=中一貫性(常に最後に更新されるノードのデータを取得)
    // 2=強一貫性(常に全てのノードのデータの更新時間を比べる)
    // ※後ほどクライアント単位で切り替える可能性あり
    private short dataConsistencyMode = 0;

    // Transactionモードで起動するかを指定
    private boolean transactionMode = false;

    // トランザクションの使用
    private String[] transactionManagerInfo = null;

    // 自身と論理的に同じQueueに紐付いているHelperの現在待機カウンター
    private AtomicInteger numberOfQueueBindWaitCounter = null;

    private static final int returnProccessingCount = 2;

    // 更新時間
    private long setTime = 0;
    private Random rnd = new Random();

    // get用文字列Buffer
    private StringBuilder getSendData = new StringBuilder(ImdstDefine.stringBufferSmallSize);

    // set用文字列Buffer
    private StringBuilder setSendData = new StringBuilder(ImdstDefine.stringBufferMiddleSize);

    // Isolationモード
    private boolean isolationMode = false;

    // Isolationモード
    private short isolationPrefixLength = 0;


    // SlaveDataNode 遅延書き込みモード(通常のsetValueのみ有効)
    private boolean slaveNodeDelayWriteMode = false;

    // ThirdDataNode 遅延書き込みモード(通常のsetValueのみ有効)
    private boolean thridNodeDelayWriteMode = false;

    // MultiGetで一度に取得するデータ量
    private volatile static int maxGetSize = ImdstDefine.maxMultiGetRequestSize;

    private boolean longReadTimeout = false;


    // クライアントからのinitメソッド用返却パラメータ
    private static String[] initReturnParam = {"0", "true", new Integer(ImdstDefine.saveDataMaxSize).toString()};


    /**
     * Logger.<br>
     */
    private static ILogger logger = LoggerFactory.createLogger(MasterManagerHelper.class);


    // 初期化メソッド定義
    public void initHelper(String initValue) {

        // データ一貫性モードの設定
        String consistencyModeStr = super.getPropertiesValue(ImdstDefine.Prop_DataConsistencyMode);

        if (consistencyModeStr != null && !consistencyModeStr.trim().equals("")) {
            dataConsistencyMode = Short.parseShort(consistencyModeStr);
        }

        // Isolationモードの設定
        if (StatusUtil.getIsolationMode()) {
            this.isolationMode = true;
            String isolationPrefixStr = StatusUtil.getIsolationPrefix();
            this.isolationPrefixLength = new Integer(isolationPrefixStr.length()).shortValue();

        }

    }


    // Jobメイン処理定義
    public String executeHelper(String optionParam) throws BatchException {
        //logger.debug("MasterManagerHelper - executeHelper - start");

        String ret = null;

        Object[] parameters = null;

        boolean closeFlg = false;

        boolean serverRunning = true;

        String pollQueueName = null;
        String[] addQueueNames = null;

        String bindQueueWaitHelperCountKey = "";
        boolean reloopSameClient = false;


        String[] retParams = null;
        String retParamStr = null;

        String clientParametersStr = null;
        String[] clientParameterList = null;
        String[] clientTargetNodes = null;

        IProtocolTaker okuyamaPorotocolTaker = null;

        // クライアントへのアウトプット(結果セット用の文字列用と、バイトデータ転送用)
        PrintWriter pw = null;
        CustomReader br = null;
        BufferedInputStream bis = null;
        Socket socket = null;
        String socketString = null;

        try{

            // パラメータ取り出し
            parameters = super.getParameters();


            // MasterManagerJobからの引数を自身に設定

            // プロトコルモードを取得
            // プロトコルに合わせてTakerを初期化
            this.protocolMode = (String)parameters[2];

            // プロトコルに合わせて作成
            this.porotocolTaker = ProtocolTakerFactory.getProtocolTaker(this.protocolMode);

            // プロトコルがokuyamaではない場合はマネージメントコマンド用のokuyamaプロトコルTakerを作成
            if (!this.protocolMode.equals("okuyama")) {
                isProtocolOkuyama = false;
                okuyamaPorotocolTaker = ProtocolTakerFactory.getProtocolTaker("okuyama");
            }

            // トランザクションロック設定
            this.transactionMode = ((Boolean)parameters[4]).booleanValue();
            // トランザクションロック-ON
            if (this.transactionMode) 
                transactionManagerInfo = (String[])parameters[5];


            // 一貫性モード初期化
            this.initConsistencyMode();

            // Queue名取得
            pollQueueName = (String)parameters[6];
            this.myPollQueue = (String)parameters[6];
            addQueueNames = (String[])parameters[7];

            // Helperの全体数と現在処理中の数を知るためのKey値
            bindQueueWaitHelperCountKey = (String)parameters[8];

            // 全体処理数を取得
            numberOfQueueBindWaitCounter = (AtomicInteger)super.getHelperShareParam(bindQueueWaitHelperCountKey);

            // Queue用の変数
            Object[] queueParam = null;
            Object[] queueMap = null;

            // 本体処理 - 終了までループ
            while(serverRunning) {
                try {

                    // 結果格納用String
                    retParams = null;
                    retParamStr = "";

                    // 切断確認
                    if (closeFlg) this.closeClientConnect(pw, br, socket);

                    // Taker初期化
                    this.porotocolTaker.init();
                    String rndVerStr1 = new Integer(rnd.nextInt(99)).toString();
                    String rndVerStr2 = new Integer(rnd.nextInt(99)).toString();

                    if (closeFlg == true || reloopSameClient == false) {

                        // Queueから処理取得
                        queueParam = super.pollSpecificationParameterQueue(pollQueueName);

                        // Queueからのパラメータ
                        queueMap = (Object[])queueParam[0];

                        // ロードバランシング指定
                        this.reverseAccess = ((Boolean)queueMap[ImdstDefine.paramBalance]).booleanValue();

                        // 一貫性レベル設定
                        if (dataConsistencyMode == 1) this.reverseAccess = true;

                        // ソケット周り(いずれクラス化する)
                        pw = (PrintWriter)queueMap[ImdstDefine.paramPw];
                        br = (CustomReader)queueMap[ImdstDefine.paramBr];
                        socket = (Socket)queueMap[ImdstDefine.paramSocket];
                        socket.setSoTimeout(0);
                        socketString = socket.toString();
                        this.porotocolTaker.setClientInfo(socketString);
                        closeFlg = false;
                    }

                    // 処理中のため待機カウンターを減算
                    if (!reloopSameClient) 
                        numberOfQueueBindWaitCounter.getAndDecrement();

                    // 同一クライアント処理フラグ初期化
                    reloopSameClient = false;

                    // クライアントからの要求を取得
                    // Takerで会話開始
                    if (isProtocolOkuyama) {
                        clientParametersStr = this.porotocolTaker.takeRequestLine(br, pw);
                        // パラメータ分解
                        clientParameterList = clientParametersStr.split(ImdstDefine.keyHelperClientParamSep);
                    } else {
                        // パラメータ分解
                        clientParameterList = this.porotocolTaker.takeRequestLine4List(br, pw);
                    }


                    if (this.porotocolTaker.nextExecution() != 1) {

                        // 処理をやり直し
                        if (this.porotocolTaker.nextExecution() == 2) { 
                            // 処理が完了したらQueueに戻す
                            queueMap[ImdstDefine.paramLast] = new Long(JavaSystemApi.currentTimeMillis);
                            queueParam[0] = queueMap;
                            super.addSmallSizeParameterQueue(addQueueNames, queueParam);
                            continue;
                        }

                        // クライアントからの要求が接続切要求ではないか確認
                        if (this.porotocolTaker.nextExecution() == 3) {
                            // 切断要求
                            closeFlg = true;
                            continue;
                        }
                    }


                    // 保存バージョン確定
                    try {
                        this.setTime = new Long(new StringBuilder(rndVerStr1).append(System.nanoTime()).append(rndVerStr2).toString());
                    } catch (NumberFormatException ne1) {
                        try {
                            this.setTime = System.nanoTime() + new Integer(rnd.nextInt(99)).intValue();
                        } catch (NumberFormatException ne2) {
                            this.setTime = System.nanoTime();
                        }
                    }

                    // ReadTimeout時間設定初期化
                    longReadTimeout = false;

                    // 本体処理開始
                    // 処理番号で処理を分岐
                    // 実行許可も判定
                    switch (StatusUtil.isExecuteMethod(Integer.parseInt(clientParameterList[0]))) {

                        case 0 :

                            // Client初期化情報
                            retParams = this.initClient();

                            break;
                        case 1 :
                            //System.out.println(new String(BASE64DecoderStream.decode(clientParameterList[1].getBytes())));

                            // Key値とValueを格納する
                            retParams = this.setKeyValue(clientParameterList[1], clientParameterList[2], clientParameterList[3], clientParameterList[4]);
                            break;
                        case 2 :
                            //System.out.println(new String(BASE64DecoderStream.decode(clientParameterList[1].getBytes())));

                            // Key値でValueを取得する
                            retParams = this.getKeyValue(clientParameterList[1]);
                            break;
                        case 3 :

                            // Tag値でキー値群を取得する
                            boolean noExistsData = true;
                            if (clientParameterList.length > 2) {

                                noExistsData = new Boolean(clientParameterList[2]).booleanValue();
                            }
                            retParams = this.getTagKeys(clientParameterList[1], noExistsData);
                            break;
                        case 4 :

                            // Tag値で紐付くキーとValueのセット配列を返す
                            break;
                        case 5 :

                            // キー値でデータを消す
                            retParams = this.removeKeyValue(clientParameterList[1], clientParameterList[2]);
                            break;
                        case 6 :

                            // Key値とValueを格納する
                            // 既に登録されている場合は失敗する
                            if (clientParameterList.length > 5) {
                                clientParameterList[4] = 
                                    clientParameterList[4] + 
                                        ImdstDefine.keyHelperClientParamSep + 
                                            clientParameterList[5];
                            }

                            retParams = this.setKeyValueOnlyOnce(clientParameterList[1], clientParameterList[2], clientParameterList[3], clientParameterList[4]);
                            break;
                        case 8 :

                            // Key値でValueを取得する(Scriptを実行する)
                            retParams = this.getKeyValueScript(clientParameterList[1], clientParameterList[2]);
                            break;
                        case 9 :

                            // Key値でValueを取得する(Scriptを実行し、更新する可能性もある)
                            retParams = this.getKeyValueScriptForUpdate(clientParameterList[1], clientParameterList[2]);
                            break;
                        case 10 :

                            // データノードを指定することで現在の詳細を取得する
                            // ノードの指定フォーマットは"IP:PORT"
                            String[] nodeDt = new String[3];
                            nodeDt[0] = "10";
                            nodeDt[1] = "true";
                            nodeDt[2] = StatusUtil.getNodeStatusDt(clientParameterList[1]);
                            retParams = nodeDt;
                            break;
                        case 12 :

                            // 自身の生存結果を返す
                            retParams = new String[3];
                            retParams[0] = "12";
                            retParams[1] = "true";
                            retParams[2] = "";
                            break;
                        case 13 :

                            // 値の加算
                            retParams = this.incrValue(clientParameterList[1], clientParameterList[2], clientParameterList[3]);
                            break;
                        case 14 :

                            // 値の減算
                            retParams = this.decrValue(clientParameterList[1], clientParameterList[2], clientParameterList[3]);
                            break;
                        case 15 :

                            // KeyでValueを取得(バージョン番号込)
                            retParams = this.getKeyValueAndVersion(clientParameterList[1]);
                            break;
                        case 16 :

                            // KeyでValueを更新(バージョンチェック込)
                            retParams = this.setKeyValueVersionCheck(clientParameterList[1], clientParameterList[2], clientParameterList[3], clientParameterList[4], clientParameterList[5]);
                            break;
                        case 22 :

                            // 複数Key値を指定することで、紐付くValueを一度に取得する(memcachedのmget)
                            // 本処理は大量のValue値を扱うため、クライアントに逐次値を返す
                            this.longReadTimeout = true;
                            int mIdx = 1;
                            int innerIdx = 0;
                            String sep = "";
                            StringBuilder requestKeysBuf = new StringBuilder();
                            ArrayList requestKeyList = new ArrayList();
                            
                            for (; mIdx < (clientParameterList.length - 1); mIdx++) {
                                innerIdx++;

                                requestKeysBuf.append(sep);
                                requestKeysBuf.append(clientParameterList[mIdx]);
                                sep = ";";
                                requestKeyList.add(clientParameterList[mIdx]);
                                // maxGetSize個のKeyをまとめて問い合わせる
                                if (!(innerIdx == MasterManagerHelper.maxGetSize) && !((mIdx+1) >= (clientParameterList.length - 1))) continue;
                                innerIdx=0;
                                sep = "";

                                // Takerで返却値を作成
                                // プロトコルがマッチしていたかをチェック
                                // 設定通りのプロトコルの場合はそのまま処理。そうでない場合はokuyamaで処理
                                String mRetParamStr = "";

                                String[] mRetParams = null;

                                if (requestKeysBuf.indexOf(";") != -1) {

                                    // 複数のKeyで問い合わせる
                                    mRetParams = this.getKeyValue(requestKeysBuf.toString(), true);
                                } else {

                                    // 1つのKeyで問い合わせる
                                    // 1つのKeyで以降の処理を正しく動かすために差を補正する
                                    mRetParams = this.getKeyValue(requestKeysBuf.toString());
                                    if (mRetParams[1].equals("true")) {
                                        mRetParams[2] = requestKeysBuf.toString() + ":" + mRetParams[2];
                                    } else {
                                        // Valueなしをマーク
                                        mRetParams[2] = requestKeysBuf.toString() + ":" + "*";
                                    }
                                }

                                String[] responseWork = mRetParams[2].split(";");
                                Map resultMap = new HashMap(MasterManagerHelper.maxGetSize);
                                for (int i = 0; i < responseWork.length; i++) {

                                    String[] oneRet = responseWork[i].split(":");

                                    resultMap.put(oneRet[0], oneRet[1]);
                                }

                                for (int i = 0; i < requestKeyList.size(); i++) {

                                    String retVal = (String)resultMap.get((String)requestKeyList.get(i));

                                    String[] oneRetParams = new String[3];
                                    oneRetParams[0] = "22";
                                    if (retVal.equals("*")) {
                                        oneRetParams[1] = "false";
                                        oneRetParams[2] = "";
                                    } else {
                                        oneRetParams[1] = "true";
                                        oneRetParams[2] = retVal;
                                    }


                                    if (this.porotocolTaker.isMatchMethod()) {

                                        mRetParamStr = this.porotocolTaker.takeResponseLine(oneRetParams);
                                    } else {
                                        okuyamaPorotocolTaker.setClientInfo(socketString);
                                        mRetParamStr = okuyamaPorotocolTaker.takeResponseLine(oneRetParams);
                                    }
                                    // クライアントへ結果書き出し
                                    pw.print(mRetParamStr);
                                    pw.flush();
                                }
                                requestKeysBuf = new StringBuilder();
                                requestKeyList = new ArrayList();
                            }

                            retParams = this.getKeyValue(clientParameterList[mIdx]);
                            if (retParams != null && retParams[0].equals("2")) retParams[0] = "22-f";   
                            break;
                        case 23 :

                            // Tag値でValue値群を取得する
                            // 本処理は大量のValue値を扱うため、クライアントに逐次値を返す
                            retParams = this.getTagKeys(clientParameterList[1], true);
                            String[] keys = new String[0];
                            if (retParams[1].equals("true")) {

                                keys = retParams[2].split(ImdstDefine.imdstTagKeyAppendSep);
                            }

                            int tagkeysIdx = 0;

                            for (; tagkeysIdx < (keys.length - 1); tagkeysIdx++) {

                                // Takerで返却値を作成
                                // プロトコルがマッチしていたかをチェック
                                // 設定通りのプロトコルの場合はそのまま処理。そうでない場合はokuyamaで処理
                                String mRetParamStr = "";
                                String[] mRetParams = this.getKeyValue(keys[tagkeysIdx]);
                                
                                if(mRetParams != null) mRetParams[0] = "23";
                                if (mRetParams[1].equals("true")) {
                                    String[] realRetParams = new String[mRetParams.length+1];
                                    realRetParams[0] = mRetParams[0];
                                    realRetParams[1] = mRetParams[1];
                                    realRetParams[2] = keys[tagkeysIdx];
                                    realRetParams[3] = mRetParams[2];
                                    if (this.porotocolTaker.isMatchMethod()) {

                                        mRetParamStr = this.porotocolTaker.takeResponseLine(realRetParams);
                                    } else {
                                        okuyamaPorotocolTaker.setClientInfo(socketString);
                                        mRetParamStr = okuyamaPorotocolTaker.takeResponseLine(realRetParams);
                                    }

                                    // クライアントへ結果書き出し
                                    pw.print(mRetParamStr);
                                    pw.flush();
                                }
                            }

                            if (keys.length > 0) {
                                retParams = this.getKeyValue(keys[tagkeysIdx]);

                                String[] realRetParams = new String[4];
                                realRetParams[0] = retParams[0];
                                realRetParams[1] = retParams[1];
                                realRetParams[2] = keys[tagkeysIdx];
                                realRetParams[3] = retParams[2];
                                retParams = realRetParams;
                            } else {
                            
                                // Tag取得の結果1件もKeyが紐付いていない場合
                                retParams = new String[2];
                                retParams[0] = "23-f";
                                retParams[1] = "false";
                            }

                            if (retParams != null && retParams[0].equals("2")) retParams[0] = "23-f";
                            break;
                        case 30 :

                            // 各キーノードへデータロック依頼
                            retParams = this.lockingData(clientParameterList[1], clientParameterList[2], clientParameterList[3], clientParameterList[4]);
                            break;
                        case 31 :

                            // 各キーノードへデータロック解除依頼
                            retParams = this.releaseLockingData(clientParameterList[1], clientParameterList[2]);
                            break;
                        case 37 :

                            // Transactionの開始を行う
                            retParams = this.startTransaction();
                            break;
                        case 38 :

                            // TransactionのCommitを行う
                            //retParams = this.commitTransaction(clientParameterList[1]);
                            break;
                        case 39 :

                            // TransactionのRollbackを行う
                            //retParams = this.rollbackTransaction(clientParameterList[1]);
                            break;
                        case 40 :

                            // 指定したKey値から指定したTag値を外す
                            // Protcol Format
                            // 40,dGFnMw==,dGFnc2FtcGxlZGF0YWtleV80Mg==,0
                            //    -------- エンコード済み削除指定タグ値
                            //             ---------------------------- 削除Key値
                            //                                          -- トランザクションコード
                            retParams = this.removeTargetTagInKey(clientParameterList[1], clientParameterList[2], clientParameterList[3]);
                            break;

                        case 42 :

                            // Key値とValueを格納する
                            // 同時に検索インデックスを作成する
                            // setKeyValueメソッドにさらにインデックス用のPrefixを最後尾文字列としてAppendしたプロトコル(指定なしは(B))
                            this.longReadTimeout = true;
                            int createIndexLen = 4;
                            if (clientParameterList.length > 6) {
                                try {
                                    createIndexLen = Integer.parseInt(clientParameterList[6]) + 1;
                                } catch (NumberFormatException nfe) {
                                    createIndexLen = 4;
                                }
                            }

                            retParams = this.setKeyValueAndCreateIndex(clientParameterList[1], clientParameterList[2], clientParameterList[3], clientParameterList[4], clientParameterList[5], createIndexLen);
                            break;
                        case 43 :

                            // 作成したIndexを使ってデータ取得を行う
                            // 送信される検索IndexはUTF-8の文字列のBASE64エンコード文字列
                            // Protcol Format
                            // 43,SGVsbG8=:b2t1eWFtYQ==,1,UHJlMQ==,5
                            //    --------------------- エンコード済み検索ワード(複数は":"で連結)
                            //                          --検索タイプ(1=AND 2=OR)
                            //                            --------- Indexプレフィックス(プレフィックスなしは(B))
                            //                                     -- 検索Wordの検索時のLength指定(Default=3)
                            this.longReadTimeout = true;
                            int searchIndexLen = 3;
                            if (clientParameterList.length > 4) {
                                try {
                                    searchIndexLen = Integer.parseInt(clientParameterList[4]);
                                } catch (NumberFormatException nfe) {
                                    searchIndexLen = 3;
                                }
                            }
                            retParams = this.searchValueIndex(clientParameterList[1], clientParameterList[2], clientParameterList[3], searchIndexLen);
                            break;
                        case 90 :

                            // KeyNodeの使用停止をマーク
                            retParams = this.pauseKeyNodeUse(clientParameterList[1]);
                            break;
                        case 91 :

                            // KeyNodeの使用再開をマーク
                            retParams = this.restartKeyNodeUse(clientParameterList[1]);
                            break;
                        case 92 :

                            // KeyNodeの復旧をマーク
                            retParams = this.arriveKeyNode(clientParameterList[1]);
                            break;
                        case 93 :

                            // 渡されたKeyNodeの使用停止をマーク
                            clientTargetNodes = clientParameterList[1].split("_");
                            for (int i = 0; i < clientTargetNodes.length; i++) {
                                retParams = this.pauseKeyNodeUse(clientTargetNodes[i]);
                            }
                            break;
                        case 94 :

                            // 渡されたKeyNodeの使用再開をマーク(複数を一度に)
                            clientTargetNodes = clientParameterList[1].split("_");
                            for (int i = 0; i < clientTargetNodes.length; i++) {
                                retParams = this.restartKeyNodeUse(clientTargetNodes[i]);
                            }
                            break;
                        case 95 :

                            // 渡されたKeyNodeの障害停止をマーク
                            retParams = this.deadKeyNode(clientParameterList[1]);
                            break;
                        case 96 :

                            // KeyNodeのリカバリー開始をマーク
                            KeyNodeConnector.setRecoverMode(true, clientParameterList[1]);
                            retParams = new String[3];
                            retParams[0] = "96";
                            retParams[1] = "true";
                            retParams[2] = "";
                            break;
                        case 97 :

                            // KeyNodeのリカバリー終了をマーク
                            KeyNodeConnector.setRecoverMode(false, "");
                            retParams = new String[3];
                            retParams[0] = "97";
                            retParams[1] = "true";
                            retParams[2] = "";
                            break;
                        case 999 :

                            // okuyamaのバージョンを返す

                            retParams = new String[3];
                            retParams[0] = "999";
                            retParams[1] = ImdstDefine.okuyamaVersion;
                            retParams[2] = "";
                            break;
                        case -1 :

                            // 実行許可なし
                            logger.info("MasterManagerHelper Execution has not been permitted  MethodNo=[" + clientParameterList[0] + "]");
                            retParams = new String[3];
                            retParams[0] = clientParameterList[0];
                            retParams[1] = "error";
                            retParams[2] = "Execution has not been permitted";
                            break;
                        default :

                            logger.info("MasterManagerHelper No Method =[" + clientParameterList[0] + "]");
                            break;
                    }

                    // Takerで返却値を作成
                    // プロトコルがマッチしていたかをチェック
                    // 設定通りのプロトコルの場合はそのまま処理。そうでない場合はokuyamaで処理
                    if (this.porotocolTaker.isMatchMethod()) {

                        retParamStr = this.porotocolTaker.takeResponseLine(retParams);
                    } else {
                        okuyamaPorotocolTaker.setClientInfo(socketString);
                        retParamStr = okuyamaPorotocolTaker.takeResponseLine(retParams);
                    }

                    // クライアントに結果送信
                    if (isProtocolOkuyama) {

                        // Okuyama
                        pw.println(retParamStr);
                    } else{

                        // Okuyama以外の場合
                        pw.print(retParamStr);
                        pw.print("\r\n");
                    }

                    // クライアントへ結果書き出し
                    pw.flush();

                    // 呼び出しをカウント
                    StatusUtil.incrementMethodExecuteCount();


                    // 処理待機中のHelper数が閾値と同じかもしくは大きい場合は同様のクライアントを処理
                    if (numberOfQueueBindWaitCounter.get() >= returnProccessingCount) {

                        try {
                            if(!br.ready()) {

                                br.mark(1);
                                socket.setSoTimeout(200);
                                int readCheck = br.read();
                                br.reset();
                                reloopSameClient = true;
                                socket.setSoTimeout(0);
                                closeFlg = false;
                            } else {

                                reloopSameClient = true;
                                socket.setSoTimeout(0);
                                closeFlg = false;
                            }
                        } catch (SocketTimeoutException ste) {

                            // 読み込みタイムアウトなら読み出し待機Queueに戻す
                            queueMap[ImdstDefine.paramLast] = new Long(JavaSystemApi.currentTimeMillis);
                            queueParam[0] = queueMap;
                            super.addSmallSizeParameterQueue(addQueueNames, queueParam);
                            reloopSameClient = false;
                        } catch (Throwable te) {

                            // エラーの場合はクローズ
                            this.closeClientConnect(pw, br, socket);
                            reloopSameClient = false;
                        }
                    } else {

                        // 処理が完了したら読み出し待機Queueに戻す
                        queueMap[ImdstDefine.paramLast] = new Long(JavaSystemApi.currentTimeMillis);
                        queueParam[0] = queueMap;
                        super.addSmallSizeParameterQueue(addQueueNames, queueParam);
                        reloopSameClient = false;
                    }
                } catch (NumberFormatException e) {

                    pw.println("-1,false,ERROR");
                    pw.flush();
                    closeFlg = true;
                    reloopSameClient = false;
                } catch (SocketException se) {

                    // クライアントとの接続が強制的に切れた場合は切断要求とみなす
                    closeFlg = true;
                    reloopSameClient = false;
                } catch (IOException ie) {

                    // 無条件で切断
                    closeFlg = true;
                    reloopSameClient = false;
                } finally {

                    // 処理待機を加算
                    if (!reloopSameClient)
                        numberOfQueueBindWaitCounter.getAndIncrement();
                }
            }

            ret = super.SUCCESS;
        } catch(Exception e) {

            logger.error("MasterManagerHelper - executeHelper - Error", e);
            ret = super.ERROR;
        } finally {
        }

        //logger.debug("MasterManagerHelper - executeHelper - end");
        return ret;
    }


    /**
     * Helper後処理.<br>
     *
     */
    public void endHelper() {
    }


    /**
     * Client初期化情報を返す.<br>
     *
     * @return String[] 結果 配列の3つ目以降が初期化情報
     * @throws BatchException
     */
    private String[] initClient() throws BatchException {
        //logger.debug("MasterManagerHelper - initClient - start");
        return MasterManagerHelper.initReturnParam;
    }



    /**
     * Key-Valueを保存する.<br>
     * 処理フロー.<br>
     * 1.DataDispatcherに依頼してTagの保存先を問い合わせる。Tag情報を全保存する<br>
     * 2.DataDispatcherに依頼してKeyの保存先を問い合わせる。Tag情報を保存する<br>
     * 3.結果文字列の配列を作成(成功時は処理番号"1"と"true"、失敗時は処理番号"1"と"false")<br>
     *
     * @param keyStr key値の文字列
     * @param tagStr tag値の文字列
     * @param isr クライアントからのインプット
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] setKeyValue(String keyStr, String tagStr, String transactionCode, String dataStr) throws BatchException {
        //logger.debug("MasterManagerHelper - setKeyValue - start");
        String[] retStrs = new String[3];

        // data部分はブランクの場合はブランク規定文字列で送られてくるのでそのまま保存する
        String[] tagKeyPair = null;
        String[] keyNodeSaveRet = null;
        String[] keyDataNodePair = null;

        // Tagは指定なしの場合はクライアントから規定文字列で送られてくるのでここでTagなしの扱いとする
        // ブランクなどでクライアントから送信するとsplit時などにややこしくなる為である。
        if (tagStr.equals(ImdstDefine.imdstBlankStrData)) tagStr = null;

        try {

            // KeyをIsolation変換実行
            keyStr = this.encodeIsolationConvert(keyStr);

            // Key値チェック
            if (!this.checkKeyLength(keyStr)) {
                // 保存失敗
                retStrs[0] = "1";
                retStrs[1] = "false";
                retStrs[2] = "Key Length Error";
                return retStrs;
            }

            // Value値チェック
            if (!this.checkValueLength(dataStr)) {
                // 保存失敗
                retStrs[0] = "1";
                retStrs[1] = "false";
                retStrs[2] = "Value Length Error";
                return retStrs;
            }


            // Tag値を保存
            if (tagStr != null && !tagStr.equals("")) {

                // Tag指定あり
                // タグとキーとのセットをタグ分保存する
                String[] tags = tagStr.split(ImdstDefine.imdstTagKeyAppendSep);

                for (int i = 0; i < tags.length; i++) {

                    // 保存先DataNode情報
                    String[] tagKeyNodeInfo = null;

                    // 複数のTagを一括保存指定されている可能性を検証
                    if (tags[i].indexOf(ImdstDefine.imdstTagBatchRegisterAppendSep) != -1) {

                        // 複数のTagを一括保存指定されている場合は保存先を1件目から決定
                        // こちらの処理は内部的なsetKeyValueAndCreateIndexからのみ呼び出されるので
                        // 絶対にLengthチェックをオーバーすることはない
                        // かつIsolation済みの値が連結されいる
                        // 複数Tagの連結セパレータは";"
                        String[] workTags = tags[i].split(ImdstDefine.imdstTagBatchRegisterAppendSep);

                        // Tag値保存先を問い合わせ

                        tagKeyNodeInfo = DataDispatcher.dispatchKeyNode(workTags[0], false);
                    } else {

                        // TagをIsolation変換実行
                        tags[i] = this.encodeIsolationConvert(tags[i]);

                        if (!this.checkKeyLength(tags[i]))  {
                            // 保存失敗
                            retStrs[0] = "1";
                            retStrs[1] = "false";
                            throw new BatchException("Tag Data Length Error");
                        }

                        // Tag値保存先を問い合わせ
                        tagKeyNodeInfo = DataDispatcher.dispatchKeyNode(tags[i], false);
                    }

                    tagKeyPair = new String[2];

                    tagKeyPair[0] = tags[i];
                    tagKeyPair[1] = keyStr;

                    // KeyNodeに接続して保存 //
                    // スレーブKeyNodeの存在有無で値を変化させる
                    if (tagKeyNodeInfo.length == 3) {
                        keyNodeSaveRet = this.setKeyNodeValue(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], null, null, null, "3", tagKeyPair, transactionCode);
                    } else if (tagKeyNodeInfo.length == 6) {
                        keyNodeSaveRet = this.setKeyNodeValue(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], tagKeyNodeInfo[3], tagKeyNodeInfo[4], tagKeyNodeInfo[5], "3", tagKeyPair, transactionCode, ImdstDefine.delayWriteFlg);
                    } else if (tagKeyNodeInfo.length == 9) {
                        keyNodeSaveRet = this.setKeyNodeValue(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], tagKeyNodeInfo[3], tagKeyNodeInfo[4], tagKeyNodeInfo[5], tagKeyNodeInfo[6], tagKeyNodeInfo[7], tagKeyNodeInfo[8], "3", tagKeyPair, transactionCode, ImdstDefine.delayWriteFlg);
                    }


                    // 保存結果確認
                    if (keyNodeSaveRet[1].equals("false")) {
                        // 保存失敗
                        retStrs[0] = "1";
                        retStrs[1] = "false";
                        retStrs[2] = keyNodeSaveRet[2];
                        throw new BatchException("Tag Data Save Error");
                    }
                }
            }

            // キー値とデータを保存
            // 保存先問い合わせ
            String[] keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, false);

            // KeyNodeに接続して保存 //
            keyDataNodePair = new String[2];
            keyDataNodePair[0] = keyStr;
            keyDataNodePair[1] = dataStr;

            // 保存実行
            // スレーブKeyNodeが存在する場合で値を変更
           if (keyNodeInfo.length == 3) {
                keyNodeSaveRet = this.setKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null, "1", keyDataNodePair, transactionCode);
            } else if (keyNodeInfo.length == 6) {
                keyNodeSaveRet = this.setKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], "1", keyDataNodePair, transactionCode, ImdstDefine.delayWriteFlg);
            } else if (keyNodeInfo.length == 9) {
                keyNodeSaveRet = this.setKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], "1", keyDataNodePair, transactionCode, ImdstDefine.delayWriteFlg);
            }


            // 保存結果確認
            if (keyNodeSaveRet[1].equals("true")) {

                retStrs[0] = "1";
                retStrs[1] = "true";
                retStrs[2] = "OK";
            } else if (keyNodeSaveRet[1].equals("false")) {

                // 保存失敗
                retStrs[0] = "1";
                retStrs[1] = "false";
                retStrs[2] = keyNodeSaveRet[2];
            } else {

                throw new BatchException("Key Data Save Error");
            }
        } catch (BatchException be) {
            logger.info("MasterManagerHelper - setKeyValue - Error", be);

            retStrs[0] = "1";
            retStrs[1] = "error";
            retStrs[2] = "NG:MasterNode - setKeyValue - Exception - " + be.toString();
        } catch (Exception e) {
            logger.info("MasterManagerHelper - setKeyValue - Error", e);
            retStrs[0] = "1";
            retStrs[1] = "false";
            retStrs[2] = "NG:MasterNode - Exception";
        }
        //logger.debug("MasterManagerHelper - setKeyValue - end");
        return retStrs;
    }


    /**
     * Key-Valueを保存する.<br>
     * ただし保存時にValueをN-gram(ユニグラム)方式にてインデックスを作成する<br>
     * インデックスはTagとして保存される<br>
     * また、転送されうValue値はUTF-8の文字コードとして処理されるので、UTF-8でなない2バイト文字はだたしく処理されない<br>
     * 
     * @param keyStr key値の文字列
     * @param tagStr tag値の文字列
     * @param transactionCode 
     * @param dataStr value値の文字列
     * @param indexPrefix 作成されたIndex(tag値)の先頭に付加する文字列
     * @param indexLength 作成するN-GramのNの部分を指定する
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] setKeyValueAndCreateIndex(String keyStr, String tagStr, String transactionCode, String dataStr, String indexPrefix, int indexLength) throws BatchException {
        // TODO:Test
        String[] retStrs = new String[3];


        try {
            if (true) {
                // Key値チェック
                if (!this.checkKeyLength(keyStr)) {
                    // 保存失敗
                    retStrs[0] = "42";
                    retStrs[1] = "false";
                    retStrs[2] = "Key Length Error";
                    return retStrs;
                }

                // Value値チェック
                if (!this.checkValueLength(dataStr)) {
                    // 保存失敗
                    retStrs[0] = "42";
                    retStrs[1] = "false";
                    retStrs[2] = "Value Length Error";
                    return retStrs;
                }

                // indexPrefixは指定なしの場合はクライアントから規定文字列で送られてくるのでここでindexPrefixなしの扱いとする
                // ブランクなどでクライアントから送信するとsplit時などにややこしくなる為である。
                if (indexPrefix.equals(ImdstDefine.imdstBlankStrData)) indexPrefix = "";


                // 古いKey－Valueのデータがある場合は取得
                // 古いValueデータから旧全文検索Wordを作りだして消す
                if (true) {
                    String[] oldValueData = this.getKeyValue(keyStr);
                    if (oldValueData != null && oldValueData[1].equals("true")) {

                        byte[] oldTestBytes = BASE64DecoderStream.decode(oldValueData[2].getBytes(ImdstDefine.characterDecodeSetBySearch));

                        String oldSIdx1 = null;
                        String oldSIdx2 = null;
                        String oldStrIdx = "";



                        String oldPrefix = (((keyStr.hashCode() << 1) >>> 1) % 8) + "_" + indexPrefix + "_";

                        String oldRealKeyStr = new String(oldTestBytes, ImdstDefine.characterDecodeSetBySearch);

                        // ユニグラム、バイグラムまで
                        // ユニグラムは漢字のみ対象
                        for (int typeIdx = 1; typeIdx < indexLength; typeIdx++) {
                            try {

                                for (int i = 0; i < ImdstDefine.saveDataMaxSize; i++) {
                                    String checkStr = oldRealKeyStr.substring(i, i+typeIdx);

                                    if(SystemUtil.checkNoIndexCharacter(checkStr)) {
                                        continue;
                                    }

                                    oldSIdx1 = new String(BASE64EncoderStream.encode((oldPrefix + checkStr).getBytes(ImdstDefine.characterDecodeSetBySearch)));

                                    String[] rmRet = this.removeTargetTagInKey(oldSIdx1, keyStr,"0");
                                }
                            } catch (Exception inE) {}
                        }
                    }
                }


                String appendTagSep = "";

                byte[] testBytes = BASE64DecoderStream.decode(dataStr.getBytes(ImdstDefine.characterDecodeSetBySearch));

                String sIdx1 = null;
                String sIdx2 = null;
                String strIdx = "";


                String prefix = (((keyStr.hashCode() << 1) >>> 1) % 8) + "_" + indexPrefix + "_";
                String realKeyStr = new String(testBytes, ImdstDefine.characterDecodeSetBySearch);

                // ユニグラム、バイグラム、ヒストグラムまで
                // ユニグラムは漢字のみ対象
                for (int typeIdx = 1; typeIdx < indexLength; typeIdx++) {
                    try {

                        for (int i = 0; i < ImdstDefine.saveDataMaxSize; i++) {
                            String checkStr = realKeyStr.substring(i, i+typeIdx);

                            if(SystemUtil.checkNoIndexCharacter(checkStr)) {
                                continue;
                            }

                            sIdx1 = new String(BASE64EncoderStream.encode((prefix + checkStr).getBytes(ImdstDefine.characterDecodeSetBySearch)));

                            strIdx = strIdx + appendTagSep + sIdx1;
                            appendTagSep = ImdstDefine.imdstTagKeyAppendSep;
                        }
                    } catch (Exception inE) {}
                }

                
                // Tagは指定なしの場合はクライアントから規定文字列で送られてくるのでここでTagなしの扱いとする
                // ブランクなどでクライアントから送信するとsplit時などにややこしくなる為である。
                if (tagStr.equals(ImdstDefine.imdstBlankStrData)) tagStr = null;

                if (tagStr != null && !tagStr.equals("")) {
                    tagStr = tagStr + ImdstDefine.imdstTagKeyAppendSep + strIdx;
                } else {
                    tagStr = strIdx;
                }
            }


            if (tagStr.indexOf(ImdstDefine.imdstTagKeyAppendSep) != -1) {

                // 複数のTagを登録しようとしている
                // Tagを保存DataNode単位でまとめる
                Map batchTagRegisterMap = new HashMap(8);

                String[] workTagList = tagStr.split(ImdstDefine.imdstTagKeyAppendSep);
                for (int i = 0; i < workTagList.length; i++) {

                    // TagをIsolation変換実行
                    workTagList[i] = this.encodeIsolationConvert(workTagList[i]);

                    if (!this.checkKeyLength(workTagList[i]))  {
                        // 保存失敗
                        retStrs[0] = "42";
                        retStrs[1] = "false";
                        throw new BatchException("Tag Data Length Error");
                    }

                    // Tag値保存先を問い合わせ
                    String[] tagKeyNodeInfo = DataDispatcher.dispatchKeyNode(workTagList[i], false);

                    if (batchTagRegisterMap.containsKey(tagKeyNodeInfo[2])) {

                        StringBuilder buildBatchTagBuf = (StringBuilder)batchTagRegisterMap.get(tagKeyNodeInfo[2]);
                        buildBatchTagBuf.append(ImdstDefine.imdstTagBatchRegisterAppendSep);
                        buildBatchTagBuf.append(workTagList[i]);
                        batchTagRegisterMap.put(tagKeyNodeInfo[2], buildBatchTagBuf);
                    } else {

                        StringBuilder buildBatchTagBuf = new StringBuilder(128);
                        buildBatchTagBuf.append(workTagList[i]);
                        batchTagRegisterMap.put(tagKeyNodeInfo[2], buildBatchTagBuf);
                    }
                }

                Set batchTagEntrySet = batchTagRegisterMap.entrySet();
                Iterator batchTagEntryIte = batchTagEntrySet.iterator(); 
                String registerTagSep = "";
                StringBuilder registerTagStrBuf = new StringBuilder(256);

                // 再度Isolation済みのタグをDataNode単位でまとめて連結
                while(batchTagEntryIte.hasNext()) {

                    Map.Entry obj = (Map.Entry)batchTagEntryIte.next();
                    String keyNodeInfoFullName = (String)obj.getKey();

                    StringBuilder buildBatchTagBuf = (StringBuilder)batchTagRegisterMap.get(keyNodeInfoFullName);

                    registerTagStrBuf.append(registerTagSep);
                    registerTagStrBuf.append(buildBatchTagBuf.toString());

                    registerTagSep = ImdstDefine.imdstTagKeyAppendSep;
                }

                // 完成したTag文字列
                tagStr = registerTagStrBuf.toString();
            }


            retStrs = setKeyValue(keyStr, tagStr, transactionCode, dataStr);
            retStrs[0] = "42";
        } catch (Exception e) {
            throw new BatchException(e);
        }
        return retStrs;
    }


    /**
     * Key-Valueを保存する.<br>
     * 既に登録済みの場合は失敗する.<br>
     * 処理フロー.<br>
     * 1.DataDispatcherに依頼してTagの保存先を問い合わせる。Tag情報を全保存する<br>
     * 2.DataDispatcherに依頼してKeyの保存先を問い合わせる。Tag情報を保存する<br>
     * 3.結果文字列の配列を作成(成功時は処理番号"1"と"true"、失敗時は処理番号"1"と"false")<br>
     *
     * @param keyStr key値の文字列
     * @param tagStr tag値の文字列
     * @param isr クライアントからのインプット
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] setKeyValueOnlyOnce(String keyStr, String tagStr, String transactionCode, String dataStr) throws BatchException {
        //logger.debug("MasterManagerHelper - setKeyValueOnlyOnce - start");
        String[] retStrs = new String[3];

        // data部分はブランクの場合はブランク規定文字列で送られてくるのでそのまま保存する
        String[] tagKeyPair = null;
        String[] keyNodeSaveRet = null;
        String[] keyDataNodePair = null;

        // Tagは指定なしの場合はクライアントから規定文字列で送られてくるのでここでTagなしの扱いとする
        // ブランクなどでクライアントから送信するとsplit時などにややこしくなる為である。
        if (tagStr.equals(ImdstDefine.imdstBlankStrData)) tagStr = null;

        try {

            // KeyをIsolation変換実行
            keyStr = this.encodeIsolationConvert(keyStr);

            // Key値チェック
            if (!this.checkKeyLength(keyStr))  {
                // 保存失敗
                retStrs[0] = "6";
                retStrs[1] = "false";
                retStrs[2] = "Key Length Error";
                return retStrs;
            }

            // Value値チェック
            if (!this.checkValueLength(dataStr))  {
                // 保存失敗
                retStrs[0] = "6";
                retStrs[1] = "false";
                retStrs[2] = "Value Length Error";
                return retStrs;
            }

            // キー値とデータを保存
            // 保存先問い合わせ
            String[] keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, false);

            // KeyNodeに接続して保存 //
            keyDataNodePair = new String[2];
            keyDataNodePair[0] = keyStr;
            keyDataNodePair[1] = dataStr;

            // 保存実行
            // スレーブKeyNodeが存在する場合で値を変更
           if (keyNodeInfo.length == 3) {
                keyNodeSaveRet = this.setKeyNodeValueOnlyOnce(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null, "1", keyDataNodePair, transactionCode);
            } else if (keyNodeInfo.length == 6) {
                keyNodeSaveRet = this.setKeyNodeValueOnlyOnce(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], "1", keyDataNodePair, transactionCode);
            } else if (keyNodeInfo.length == 9) {
                keyNodeSaveRet = this.setKeyNodeValueOnlyOnce(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], "1", keyDataNodePair, transactionCode);
            }


            // 保存結果確認
            if (keyNodeSaveRet[1].equals("false")) {
                // 保存失敗
                retStrs[0] = "6";
                retStrs[1] = "false";
                retStrs[2] = keyNodeSaveRet[2];

                return retStrs;
            }


            // Tag値を保存
            if (tagStr != null && !tagStr.equals("")) {

                // Tag指定あり
                // タグとキーとのセットをタグ分保存する
                String[] tags = tagStr.split(ImdstDefine.imdstTagKeyAppendSep);

                for (int i = 0; i < tags.length; i++) {
                    // TagをIsolation変換実行
                    tags[i] = this.encodeIsolationConvert(tags[i]);

                    if (!this.checkKeyLength(tags[i]))  {
                        // 保存失敗
                        retStrs[0] = "6";
                        retStrs[1] = "false";
                        throw new BatchException("Tag Length Error");
                    }

                    // Tag値保存先を問い合わせ
                    String[] tagKeyNodeInfo = DataDispatcher.dispatchKeyNode(tags[i], false);
                    tagKeyPair = new String[2];

                    tagKeyPair[0] = tags[i];
                    tagKeyPair[1] = keyStr;

                    // KeyNodeに接続して保存 //
                    // スレーブKeyNodeの存在有無で値を変化させる
                    if (tagKeyNodeInfo.length == 3) {
                        keyNodeSaveRet = this.setKeyNodeValue(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], null, null, null, "3", tagKeyPair, transactionCode);
                    } else if (tagKeyNodeInfo.length == 6) {
                        keyNodeSaveRet = this.setKeyNodeValue(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], tagKeyNodeInfo[3], tagKeyNodeInfo[4], tagKeyNodeInfo[5], "3", tagKeyPair, transactionCode);
                    } else if (tagKeyNodeInfo.length == 9) {
                        keyNodeSaveRet = this.setKeyNodeValue(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], tagKeyNodeInfo[3], tagKeyNodeInfo[4], tagKeyNodeInfo[5], tagKeyNodeInfo[6], tagKeyNodeInfo[7], tagKeyNodeInfo[8], "3", tagKeyPair, transactionCode);
                    }


                    // 保存結果確認
                    if (keyNodeSaveRet[1].equals("false")) {
                        // 保存失敗
                        retStrs[0] = "6";
                        retStrs[1] = "false";
                        retStrs[2] = keyNodeSaveRet[2];
                        throw new BatchException("Tag Data Save Error");
                    }
                }
            }

            retStrs[0] = "6";
            retStrs[1] = "true";
            retStrs[2] = "OK";

        } catch (BatchException be) {

            logger.info("MasterManagerHelper - setKeyValueOnlyOnce - Error", be);
            retStrs[0] = "6";
            retStrs[1] = "error";
            retStrs[2] = "NG:MasterNode - Exception";
        } catch (Exception e) {
            logger.info("MasterManagerHelper - setKeyValueOnlyOnce - Error", e);
            retStrs[0] = "6";
            retStrs[1] = "false";
            retStrs[2] = "MasterNode - Exception";
        }
        //logger.debug("MasterManagerHelper - setKeyValueOnlyOnce - end");
        return retStrs;
    }


    /**
     * Key-Valueを保存する.<br>
     * バージョン番号をチェックして異なる場合は失敗する.<br>
     *
     * @param keyStr key値の文字列
     * @param tagStr tag値の文字列
     * @param transactionCode 
     * @param dataStr 
     * @param checkVersionNo
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] setKeyValueVersionCheck(String keyStr, String tagStr, String transactionCode, String dataStr, String checkVersionNo) throws BatchException {
        //logger.debug("MasterManagerHelper - setKeyValueVersionCheck - start");
        String[] retStrs = new String[3];

        // data部分はブランクの場合はブランク規定文字列で送られてくるのでそのまま保存する
        String[] tagKeyPair = null;
        String[] keyNodeSaveRet = null;
        String[] keyDataNodePair = null;

        // Tagは指定なしの場合はクライアントから規定文字列で送られてくるのでここでTagなしの扱いとする
        // ブランクなどでクライアントから送信するとsplit時などにややこしくなる為である。
        if (tagStr.equals(ImdstDefine.imdstBlankStrData)) tagStr = null;

        try {

            // KeyをIsolation変換実行
            keyStr = this.encodeIsolationConvert(keyStr);

            // Key値チェック
            if (!this.checkKeyLength(keyStr))  {
                // 保存失敗
                retStrs[0] = "16";
                retStrs[1] = "false";
                retStrs[2] = "Key Length Error";
                return retStrs;
            }

            // Value値チェック
            if (!this.checkValueLength(dataStr))  {
                // 保存失敗
                retStrs[0] = "16";
                retStrs[1] = "false";
                retStrs[2] = "Value Length Error";
                return retStrs;
            }

            // キー値とデータを保存
            // 保存先問い合わせ
            String[] keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, false);

            // KeyNodeに接続して保存 //
            keyDataNodePair = new String[2];
            keyDataNodePair[0] = keyStr;
            keyDataNodePair[1] = dataStr;

            // 保存実行
            // スレーブKeyNodeが存在する場合で値を変更
           if (keyNodeInfo.length == 3) {
                keyNodeSaveRet = this.setKeyNodeValueVersionCheck(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null, "1", keyDataNodePair, transactionCode, checkVersionNo, true);
            } else if (keyNodeInfo.length == 6) {
                keyNodeSaveRet = this.setKeyNodeValueVersionCheck(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], "1", keyDataNodePair, transactionCode, checkVersionNo, true);
            } else if (keyNodeInfo.length == 9) {
                keyNodeSaveRet = this.setKeyNodeValueVersionCheck(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], "1", keyDataNodePair, transactionCode, checkVersionNo);
            }


            // 保存結果確認
            if (keyNodeSaveRet[1].equals("false")) {
                // 保存失敗
                retStrs[0] = "16";
                retStrs[1] = "false";
                retStrs[2] = keyNodeSaveRet[2];

                return retStrs;
            }


            // Tag値を保存
            if (tagStr != null && !tagStr.equals("")) {

                // Tag指定あり
                // タグとキーとのセットをタグ分保存する
                String[] tags = tagStr.split(ImdstDefine.imdstTagKeyAppendSep);

                for (int i = 0; i < tags.length; i++) {

                    // TagをIsolation変換
                    tags[i] = this.encodeIsolationConvert(tags[i]);

                    if (!this.checkKeyLength(tags[i]))  {
                        // 保存失敗
                        retStrs[0] = "16";
                        retStrs[1] = "false";
                        throw new BatchException("Tag Length Error");
                    }

                    // Tag値保存先を問い合わせ
                    String[] tagKeyNodeInfo = DataDispatcher.dispatchKeyNode(tags[i], false);
                    tagKeyPair = new String[2];

                    tagKeyPair[0] = tags[i];
                    tagKeyPair[1] = keyStr;

                    // KeyNodeに接続して保存 //
                    // スレーブKeyNodeの存在有無で値を変化させる
                    if (tagKeyNodeInfo.length == 3) {
                        keyNodeSaveRet = this.setKeyNodeValue(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], null, null, null, "3", tagKeyPair, transactionCode);
                    } else if (tagKeyNodeInfo.length == 6) {
                        keyNodeSaveRet = this.setKeyNodeValue(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], tagKeyNodeInfo[3], tagKeyNodeInfo[4], tagKeyNodeInfo[5], "3", tagKeyPair, transactionCode);
                    } else if (tagKeyNodeInfo.length == 9) {
                        keyNodeSaveRet = this.setKeyNodeValue(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], tagKeyNodeInfo[3], tagKeyNodeInfo[4], tagKeyNodeInfo[5], tagKeyNodeInfo[6], tagKeyNodeInfo[7], tagKeyNodeInfo[8], "3", tagKeyPair, transactionCode);
                    }


                    // 保存結果確認
                    if (keyNodeSaveRet[1].equals("false")) {
                        // 保存失敗
                        retStrs[0] = "16";
                        retStrs[1] = "false";
                        retStrs[2] = keyNodeSaveRet[2];
                        throw new BatchException("Tag Data Save Error");
                    }
                }
            }

            retStrs[0] = "16";
            retStrs[1] = "true";
            retStrs[2] = "OK";

        } catch (BatchException be) {

            logger.info("MasterManagerHelper - setKeyValueVersionCheck - Error", be);
            retStrs[0] = "16";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
        } catch (Exception e) {
            logger.info("MasterManagerHelper - setKeyValueVersionCheck - Error", e);
            retStrs[0] = "16";
            retStrs[1] = "false";
            retStrs[2] = "MasterNode - Exception";
        }
        //logger.debug("MasterManagerHelper - setKeyValueVersionCheck - end");
        return retStrs;
    }


    /**
     * KeyでValueを取得する.<br>
     * 処理フロー.<br>
     * 1.DataDispatcherにKeyを使用してValueの保存先を問い合わせる<br>
     * 2.KeyNodeに接続してValueを取得する<br>
     * 3.結果文字列の配列を作成(成功時は処理番号"2"と"true"とValue、失敗時は処理番号"2"と"false"とValue)<br>
     *
     *
     * @param keyStr key値の文字列
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] getKeyValue(String keyStr) throws BatchException {
        return getKeyValue(keyStr, false);
    }

    private String[] getKeyValue(String keyStr, boolean multiGet) throws BatchException {
        //logger.debug("MasterManagerHelper - getKeyValue - start");
        String[] retStrs = new String[3];

        String[] keyNodeSaveRet = null;
        String[] keyNodeInfo = null;

        try {

            // Isolation変換実行
            if (!multiGet) {

                keyStr = this.encodeIsolationConvert(keyStr);
                if (!this.checkKeyLength(keyStr)) {
                    // 保存失敗
                    retStrs[0] = "2";
                    retStrs[1] = "false";
                    retStrs[2] = "Key Length Error";
                    return retStrs;
                }

                keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, this.reverseAccess);


                // 取得実行
                if (keyNodeInfo.length == 3) {
                    keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null,  "2", keyStr);
                } else if (keyNodeInfo.length == 6) {
                    keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], "2", keyStr);
                } else if (keyNodeInfo.length == 9) {
                    keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], "2", keyStr);
                }


                // 過去に別ルールを設定している場合は過去ルール側でデータ登録が行われている可能性があるの
                // でそちらのルールでのデータ格納場所も調べる
                if (keyNodeSaveRet[1].equals("false")) {

                    //System.out.println("過去ルールを探索 - get(" + keyNodeInfo[2] + ") =" + new String(BASE64DecoderStream.decode(keyStr.getBytes())));
                    // キー値を使用して取得先を決定
                    // 過去ルールがなくなれば終了
                    for (int i = 0; (keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, this.reverseAccess, i)) != null; i++) {

                        // 取得実行
                        if (keyNodeInfo.length == 3) {
                            keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null, "2", keyStr);
                        } else if (keyNodeInfo.length == 6) {
                            keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], "2", keyStr);
                        } else if (keyNodeInfo.length == 9) {
                            keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], "2", keyStr);
                        }


                        // 過去ルールからデータを発見
                        if (keyNodeSaveRet[1].equals("true")) {
                            //System.out.println("過去ルールからデータを発見 =[" + keyNodeInfo[2] + "]");
                            break;
                        }
                    }
                }


                // 取得結果確認
                if (keyNodeSaveRet[1].equals("false")) {

                    // 取得失敗(データなし)
                    retStrs[0] = keyNodeSaveRet[0];
                    retStrs[1] = "false";
                    retStrs[2] = "";
                } else {

                    retStrs[0] = keyNodeSaveRet[0];
                    retStrs[1] = "true";
                    retStrs[2] = keyNodeSaveRet[2];
                }

            } else {

                // 複数Key一括取得
                // 複数一括指定の場合は、Keyを";"で連結してDataNodeへ転送する
                // 一括転送されたKey値をセパレート文字である";"で分解して問い合わせ先DataNode単位でまとめる
                String[] keys = keyStr.split(";");
                Map requestKeyMap = new HashMap();
                Map requestNodeMap = new HashMap();
                for (int i = 0; i < keys.length; i++) {

                    keys[i] = this.encodeIsolationConvert(keys[i]);

                    keyNodeInfo = DataDispatcher.dispatchKeyNode(keys[i], this.reverseAccess);
                    StringBuilder buf = (StringBuilder)requestKeyMap.get(keyNodeInfo[2]);
                    if (buf == null) {
                        buf = new StringBuilder();
                        buf.append(keys[i]);
                    } else {

                        buf.append(";");
                        buf.append(keys[i]);
                    }
                    requestKeyMap.put(keyNodeInfo[2], buf);
                    requestNodeMap.put(keyNodeInfo[2], keyNodeInfo);
                }

                Set entrySet = requestNodeMap.entrySet();
                Iterator entryIte = entrySet.iterator(); 
                StringBuilder lastResultBuf = new StringBuilder(1024);
                String lastRetSep = "";

                while(entryIte.hasNext()) {

                    Map.Entry obj = (Map.Entry)entryIte.next();
                    String nodeFullName = (String)obj.getKey();

                    keyNodeInfo = (String[])requestNodeMap.get(nodeFullName);
                    StringBuilder buf = (StringBuilder)requestKeyMap.get(nodeFullName);
                    String getKeysStr = buf.toString();
                    if (getKeysStr.indexOf(";") == -1) buf.append(";");
                    getKeysStr = null;

                    // 取得実行
                    if (keyNodeInfo.length == 3) {
                        keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null,  "2", buf.toString());
                    } else if (keyNodeInfo.length == 6) {
                        keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], "2", buf.toString());
                    } else if (keyNodeInfo.length == 9) {
                        keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], "2", buf.toString());
                    }
                    if (keyNodeSaveRet != null && keyNodeSaveRet[0].equals("2") && keyNodeSaveRet[1].equals("true")) {

                        // Isolation文字列を外す
                        String resultStr = keyNodeSaveRet[2];

                        String[] multiresult = resultStr.split(";");

                        for (int idx = 0; idx < multiresult.length; idx++) {

                            String[] isolationWorlRet = multiresult[idx].split(":");

                            isolationWorlRet[0] = this.decodeIsolationConvert(isolationWorlRet[0]);
                            lastResultBuf.append(lastRetSep);
                            lastResultBuf.append(isolationWorlRet[0]);
                            lastResultBuf.append(":");
                            lastResultBuf.append(isolationWorlRet[1]);
                            lastRetSep = ";";
                        }

                    }
                }

                // 最終結果を格納
                retStrs[0] = "2";
                retStrs[1] = "true";
                retStrs[2] = lastResultBuf.toString(); //Key:Value;Key:Value;Key:Value("*"の場合は値が存在しないマーク値)
            }

        } catch (BatchException be) {
            logger.error("MasterManagerHelper - getKeyValue - Error", be);

            retStrs[0] = "2";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
        } catch (Exception e) {
            logger.error("MasterManagerHelper - getKeyValue - Error", e);

            retStrs[0] = "2";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
        }
        //logger.debug("MasterManagerHelper - getKeyValue - end");
        return retStrs;
    }


    /**
     * 作成済みIndexを利用して対象Key値を取得する.<br>
     *
     * @param indexStrs 取得対象文字列群(BASE64エンコード済みでWordの区切りは":")
     * @param searchType 1=AND 2=OR
     * @param indexPrefix
     * @param searchIndexLength ここで指定した長さのN-GramIndexが存在するものとして探す
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] searchValueIndex(String indexStrs, String searchType, String indexPrefix, int searchIndexLength) throws BatchException {
        //logger.debug("MasterManagerHelper - searchValueIndex - start");
        String[] retStrs = new String[3];
        StringBuilder retKeysBuf = new StringBuilder();
        String retKeysSep = "";
        try {

            if (indexStrs.length() < 1)  {
                // 失敗
                retStrs[0] = "43";
                retStrs[1] = "false";
                retStrs[2] = "Search Keyword Length Error";
                retStrs[3] = "";
                return retStrs;
            }
            //long start1 = System.nanoTime();

            // Prefixを調整
            if (indexPrefix.equals(ImdstDefine.imdstBlankStrData)) indexPrefix = "";

            String[] workKeywords = indexStrs.split(":");
            ArrayList decodeWorkKeywords = new ArrayList(5);
            ArrayList allSearchWordList = new ArrayList();
            ArrayList fullMatchList = new ArrayList();

            for (int idx = 0; idx < workKeywords.length; idx++) {
                boolean fullMatch = false;
                // デコードして復元
                String workStr = new String(BASE64DecoderStream.decode(workKeywords[idx].getBytes(ImdstDefine.characterDecodeSetBySearch)), ImdstDefine.characterDecodeSetBySearch);


                String keyword = "";

                if (workStr.length() > searchIndexLength) {

                    // 指定サイズ
                    keyword = workStr.substring(0, searchIndexLength);
                } else if (workStr.length() <= searchIndexLength) {

                    keyword = workStr;
                    fullMatch = true;
                } else if (searchIndexLength > 3 && workStr.length() > 3) {

                    searchIndexLength = workStr.length();
                    keyword = workStr.substring(0, searchIndexLength);
                } else {
                    if (workStr.length() > 2) {

                        // ヒストグラム以上
                        keyword = workStr.substring(0, 3);
                    } else if (workStr.length() > 1) {

                        // バイグラム以上
                        keyword = workStr.substring(0, 2);

                    } else {

                        // ユニグラム
                        keyword = workStr;

                    }
                }
                // 検索対象か調べる
                if(SystemUtil.checkNoIndexCharacter(keyword)) {

                    continue;
                }

                // デコード済みキーワードを蓄える
                decodeWorkKeywords.add(workStr);

                String[] singleWordList = new String[8];
                for (int i = 0; i < 8; i++) {
                    singleWordList[i] = new String(BASE64EncoderStream.encode((i + "_" + indexPrefix + "_" + keyword).getBytes(ImdstDefine.characterDecodeSetBySearch)));
                }
                allSearchWordList.add(singleWordList);
                fullMatchList.add(new Boolean(fullMatch));
            }

            HashMap retMap = new HashMap(256);
            HashMap fullMatchKeyMap = new HashMap(256);

            //long start2 = System.nanoTime();

            for (int idx = 0; idx < allSearchWordList.size(); idx++) {
                String[] singleWordList = (String[])allSearchWordList.get(idx);
                boolean fullMatchFlg = ((Boolean)fullMatchList.get(idx)).booleanValue();

                for (int i = 0; i < singleWordList.length; i++) {
                    String[] ret = this.getTagKeys(singleWordList[i], true);
                    if (ret[0].equals("4") && ret[1].equals("true")) {
                        // 該当あり
                        String targetKeysStr = ret[2];
                        String[] targetKeyList = targetKeysStr.split(ImdstDefine.imdstTagKeyAppendSep);
                        for (int ii = 0; ii < targetKeyList.length; ii++) {

                            if (!retMap.containsKey(targetKeyList[ii]) && fullMatchFlg == true) {
                                fullMatchKeyMap.put(targetKeyList[ii], "");
                            } else {
                                retMap.put(targetKeyList[ii], "");
                                if (fullMatchKeyMap.containsKey(targetKeyList[ii])) fullMatchKeyMap.remove(targetKeyList[ii]);
                            }
                        }
                    }
                }
            }

            //long end2 = System.nanoTime();

            Map targetSumKeysMap = new HashMap();
            Map targetNodeInfoMap = new HashMap();
            // 該当データ次第で処理分岐
            if (retMap.size() > 0) {

                // 該当データあり
                Set entrySet = retMap.entrySet();
                Iterator entryIte = entrySet.iterator(); 
                int nodeNamePrefix = 0;
                int cnt = 0;
                while(entryIte.hasNext()) {
                    cnt++;
                    if ((cnt % 5000) == 0) nodeNamePrefix++;
                    Map.Entry obj = (Map.Entry)entryIte.next();
                    String key = (String)obj.getKey();

                    // Isolation変換実行
                    String cnvIsolationKey = this.encodeIsolationConvert(key);

                    // キー値を使用して取得先を決定
                    String[] keyNodeInfo = DataDispatcher.dispatchKeyNode(cnvIsolationKey, this.reverseAccess);
                    String prefixFullNodeName = nodeNamePrefix + "_" + keyNodeInfo[2];

                    if (targetSumKeysMap.containsKey(prefixFullNodeName)) {

                        StringBuilder keyBuf = (StringBuilder)targetSumKeysMap.get(prefixFullNodeName);
                        keyBuf.append(":");
                        keyBuf.append(cnvIsolationKey);
                        targetSumKeysMap.put(prefixFullNodeName, keyBuf);
                    } else {
    
                        targetNodeInfoMap.put(prefixFullNodeName, keyNodeInfo);
                        StringBuilder keyBuf = new StringBuilder(256);
                        keyBuf.append(cnvIsolationKey);
                        targetSumKeysMap.put(prefixFullNodeName, keyBuf);
                    }
                }


                // Nodeへマッチ実行
                Set nodeEntrySet = targetSumKeysMap.entrySet();
                Iterator nodeEntryIte = nodeEntrySet.iterator(); 
                String matchKeySep = "";
                String[] keyNodeInfo = null;
                String[] keyNodeSaveRet = null;

                // RequestをDataNodeへ一旦全台送信
                while(nodeEntryIte.hasNext()) {

                    Map.Entry obj = (Map.Entry)nodeEntryIte.next();
                    String nodeFullName = (String)obj.getKey();

                    StringBuilder keys = (StringBuilder)targetSumKeysMap.get(nodeFullName);
                    keyNodeInfo = (String[])targetNodeInfoMap.get(nodeFullName);
                    KeyNodeConnector keyNodeConnector = null;

                    int useNodeIdx = 0;

                    // KeyNodeとの接続を確立
                    try {
                        useNodeIdx = 1;

                        keyNodeConnector = this.createKeyNodeConnection(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], false);

                        if (keyNodeConnector == null && keyNodeInfo.length > 3) {
                            useNodeIdx = 2;
                            keyNodeConnector = this.createKeyNodeConnection(keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], false);
                        }

                        // 再接続
                        if (keyNodeConnector == null) {
                            useNodeIdx = 1;
                            keyNodeConnector = this.createKeyNodeConnection(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], false);
                        }

                        if (keyNodeConnector == null && keyNodeInfo.length > 6) {
                            useNodeIdx = 3;
                            keyNodeConnector = this.createKeyNodeConnection(keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], false);
                        }

                        if (keyNodeConnector == null) {
                            throw new BatchException("DataNode Connect Error");
                        }


                        // Key値でValueを取得
                        StringBuilder buf = new StringBuilder(ImdstDefine.stringBufferMiddleSize);
                        String sendStr = null;

                        // パラメータ作成 処理タイプ[セパレータ]キー値のハッシュ値文字列
                        buf.append("50");
                        buf.append(ImdstDefine.keyHelperClientParamSep);
                        buf.append(this.stringCnv(keys.toString()));
                        buf.append(ImdstDefine.keyHelperClientParamSep);
                        buf.append(indexStrs);
                        buf.append(ImdstDefine.keyHelperClientParamSep);
                        buf.append(searchType);

                        // 送信
                        keyNodeConnector.println(buf.toString());
                        keyNodeConnector.flush();
                        targetNodeInfoMap.put(nodeFullName + "_CONNECT", keyNodeConnector);
                    } catch (Exception inEx1) {
                        keyNodeConnector = null;
                        targetNodeInfoMap.put(nodeFullName + "_CONNECT", null);
                        throw inEx1;
                    }
                }


                // 結果を回収
                nodeEntrySet = targetSumKeysMap.entrySet();
                nodeEntryIte = nodeEntrySet.iterator(); 
                matchKeySep = "";

                while(nodeEntryIte.hasNext()) {
                    Map.Entry obj = (Map.Entry)nodeEntryIte.next();
                    String nodeFullName = (String)obj.getKey();

                    StringBuilder keys = (StringBuilder)targetSumKeysMap.get(nodeFullName);
                    keyNodeInfo = (String[])targetNodeInfoMap.get(nodeFullName);
                    KeyNodeConnector keyNodeConnector = (KeyNodeConnector)targetNodeInfoMap.get(nodeFullName + "_CONNECT");

                    // 実行
                    if (keyNodeInfo.length == 3) {
                        keyNodeSaveRet = this.matchTargetKeyPairValueCharacter(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null, keys.toString(), indexStrs, searchType, keyNodeConnector);
                    } else if (keyNodeInfo.length == 6) {
                        keyNodeSaveRet = this.matchTargetKeyPairValueCharacter(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keys.toString(), indexStrs, searchType, keyNodeConnector);
                    } else if (keyNodeInfo.length == 9) {
                        keyNodeSaveRet = this.matchTargetKeyPairValueCharacter(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], keys.toString(), indexStrs, searchType, keyNodeConnector);
                    }

                    // 取得結果確認
                    if (keyNodeSaveRet[1].equals("true")) {

                        retKeysBuf.append(matchKeySep);

                        if (keyNodeSaveRet[2].indexOf(ImdstDefine.imdstTagKeyAppendSep) != -1) {

                            String[] isolationDecodeWorkList = keyNodeSaveRet[2].split(ImdstDefine.imdstTagKeyAppendSep);

                            StringBuilder newDecodeKeyBuf = new StringBuilder(keyNodeSaveRet[2].length());
                            String newDecodeSep = "";
                            for (int idx = 0; idx < isolationDecodeWorkList.length; idx++) {

                                newDecodeKeyBuf.append(newDecodeSep);
                                newDecodeKeyBuf.append(this.decodeIsolationConvert(isolationDecodeWorkList[idx]));
                                newDecodeSep = ImdstDefine.imdstTagKeyAppendSep;
                            }
                            keyNodeSaveRet[2] = newDecodeKeyBuf.toString();
                            newDecodeKeyBuf = null;
                        } else {

                            keyNodeSaveRet[2] = this.decodeIsolationConvert(keyNodeSaveRet[2]);
                        }
                        retKeysBuf.append(keyNodeSaveRet[2]);
                        matchKeySep = ":";
                    }
                }


                // 最終的な返却値を作成
                retStrs[0] = "43";

                // 完全に検索Keywordが一致したものを連結
                //System.out.println("111111111111111111111");
                //System.out.println(fullMatchKeyMap);

                if (fullMatchKeyMap.size() > 0) {

                    String fullMatchKeySep = "";
                    if (retKeysBuf.length() > 0) {
                        fullMatchKeySep = ":";
                    }

                    Set fullMatchEntrySet = fullMatchKeyMap.entrySet();
                    Iterator fullMatchEntryIte = fullMatchEntrySet.iterator(); 

                    while(fullMatchEntryIte.hasNext()) {
                        Map.Entry obj = (Map.Entry)fullMatchEntryIte.next();
                        String fullMatchKeyCharacter = (String)obj.getKey();
                        retKeysBuf.append(fullMatchKeySep);
                        retKeysBuf.append(fullMatchKeyCharacter);
                        fullMatchKeySep = ":";
                    }
                }

                if (retKeysBuf.length() > 0) {
                    retStrs[1] = "true";
                } else {
                    retStrs[1] = "false";
                }
                retStrs[2] = retKeysBuf.toString();
            } else if (fullMatchKeyMap.size() > 0) {

                // 最終的な返却値を作成
                retStrs[0] = "43";

                // 完全に検索Keywordが一致したものを連結
                if (fullMatchKeyMap.size() > 0) {

                    String fullMatchKeySep = "";
                    if (retKeysBuf.length() > 0) {
                        fullMatchKeySep = ":";
                    }

                    //System.out.println("222222222222222222222222");
                    //System.out.println(fullMatchKeyMap);
                    Set fullMatchEntrySet = fullMatchKeyMap.entrySet();
                    Iterator fullMatchEntryIte = fullMatchEntrySet.iterator(); 

                    while(fullMatchEntryIte.hasNext()) {
                        Map.Entry obj = (Map.Entry)fullMatchEntryIte.next();
                        String fullMatchKeyCharacter = (String)obj.getKey();
                        retKeysBuf.append(fullMatchKeySep);
                        retKeysBuf.append(fullMatchKeyCharacter);
                        fullMatchKeySep = ":";
                    }
                }

                if (retKeysBuf.length() > 0) {
                    retStrs[1] = "true";
                } else {
                    retStrs[1] = "false";
                }
                retStrs[2] = retKeysBuf.toString();
            } else {
            
                // 該当データなし
                retStrs[0] = "43";
                retStrs[1] = "false";
                retStrs[2] = "";
            }
            //long end1 = System.nanoTime();
            //System.out.println("Time2=" + (end2 - start2) + " Time1=" + (end1 - start1));

        } catch (BatchException be) {
            logger.error("MasterManagerHelper - searchValueIndex - Error", be);
        } catch (Exception e) {
            logger.error("MasterManagerHelper - searchValueIndex - Exception", e);
            retStrs[0] = "43";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
        }

        //logger.debug("MasterManagerHelper - searchValueIndex - end");
        return retStrs;
    }



    /**
     * KeyでValueを取得する.<br>
     * 合わせてバージョン番号も返す.<br>
     * 処理フロー.<br>
     * 1.DataDispatcherにKeyを使用してValueの保存先を問い合わせる<br>
     * 2.KeyNodeに接続してValueを取得する<br>
     * 3.結果文字列の配列を作成(成功時は処理番号"15"と"true"とValue、失敗時は処理番号"15"と"false"とValue)<br>
     *
     *
     * @param keyStr key値の文字列
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] getKeyValueAndVersion(String keyStr) throws BatchException {

        String[] retStrs = new String[4];

        String[] keyNodeSaveRet = null;
        String[] keyNodeInfo = null;

        try {

            // Isolation変換実行
            keyStr = this.encodeIsolationConvert(keyStr);

            if (!this.checkKeyLength(keyStr))  {
                // 保存失敗
                retStrs[0] = "15";
                retStrs[1] = "false";
                retStrs[2] = "Key Length Error";
                retStrs[3] = "";
                return retStrs;
            }

           keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, this.reverseAccess);

            // 取得実行
            if (keyNodeInfo.length == 3) {
                keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null,  "2", keyStr, true);
            } else if (keyNodeInfo.length == 6) {
                keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], "2", keyStr, true);
            } else if (keyNodeInfo.length == 9) {
                keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], "2", keyStr, true);
            }


            // 過去に別ルールを設定している場合は過去ルール側でデータ登録が行われている可能性があるの
            // でそちらのルールでのデータ格納場所も調べる
            if (keyNodeSaveRet[1].equals("false")) {

                // キー値を使用して取得先を決定
                // 過去ルールがなくなれば終了
                for (int i = 0; (keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, this.reverseAccess, i)) != null; i++) {

                    // 取得実行
                    if (keyNodeInfo.length == 3) {
                        keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null, "2", keyStr, true);
                    } else if (keyNodeInfo.length == 6) {
                        keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], "2", keyStr, true);
                    } else if (keyNodeInfo.length == 9) {
                        keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], "2", keyStr, true);
                    }


                    // 過去ルールからデータを発見
                    if (keyNodeSaveRet[1].equals("true")) {
                        //System.out.println("過去ルールからデータを発見 =[" + keyNodeInfo[2] + "]");
                        break;
                    }
                }
            }


            // 取得結果確認
            if (keyNodeSaveRet[1].equals("false")) {

                // 取得失敗(データなし)
                retStrs[0] = "15";
                retStrs[1] = "false";
                retStrs[2] = "";
                retStrs[3] = "";
            } else {

                retStrs[0] = "15";
                retStrs[1] = "true";
                retStrs[2] = keyNodeSaveRet[2];
                retStrs[3] = keyNodeSaveRet[3];
            }

        } catch (BatchException be) {
            logger.error("MasterManagerHelper - getKeyValueAndVersion - Error", be);

            retStrs[0] = "15";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
            retStrs[3] = "";
        } catch (Exception e) {
            logger.error("MasterManagerHelper - getKeyValueAndVersion - Error", e);

            retStrs[0] = "15";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
            retStrs[3] = "";
        }

        return retStrs;
    }


    /**
     * KeyでValueを取得する.<br>
     * Scriptも同時に実行する.<br>
     * 処理フロー.<br>
     * 1.DataDispatcherにKeyを使用してValueの保存先を問い合わせる<br>
     * 2.KeyNodeに接続してValueを取得する<br>
     * 3.結果文字列の配列を作成(成功時は処理番号"8"と"true"とValue、データが存在しない場合は処理番号"8"と"false"とValue、スクリプトエラー時は処理番号"8"と"error"とエラーメッセージ)<br>
     *
     *
     * @param keyStr key値の文字列
     * @param scriptStr 実行Scriptの文字列
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] getKeyValueScript(String keyStr, String scriptStr) throws BatchException {
        //logger.debug("MasterManagerHelper - getKeyValueScript - start");
        String[] retStrs = new String[3];

        String[] keyNodeSaveRet = null;
        String[] keyNodeInfo = null;

        try {
            // Isolation変換実行
            keyStr = this.encodeIsolationConvert(keyStr);

            if (!this.checkKeyLength(keyStr))  {
                // 保存失敗
                retStrs[0] = "8";
                retStrs[1] = "false";
                retStrs[2] = "Key Length Error";
                return retStrs;
            }

            // キー値を使用して取得先を決定
            keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, this.reverseAccess);

            // 取得実行
            if (keyNodeInfo.length == 3) {
                keyNodeSaveRet = this.getKeyNodeValueScript(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null,  "8", keyStr, scriptStr);
            } else if (keyNodeInfo.length == 6) {
                keyNodeSaveRet = this.getKeyNodeValueScript(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], "8", keyStr, scriptStr);
            } else if (keyNodeInfo.length == 9) {
                keyNodeSaveRet = this.getKeyNodeValueScript(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], "8", keyStr, scriptStr);
            }


            // 過去に別ルールを設定している場合は過去ルール側でデータ登録が行われている可能性があるの
            // でそちらのルールでのデータ格納場所も調べる
            if (keyNodeSaveRet[1].equals("false")) {

                //System.out.println("過去ルールを探索 - getScript =" + new String(BASE64DecoderStream.decode(keyStr.getBytes())));
                // キー値を使用して取得先を決定
                for (int i = 0; (keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, this.reverseAccess, i)) != null; i++) {

                    // 取得実行
                    if (keyNodeInfo.length == 3) {
                        keyNodeSaveRet = this.getKeyNodeValueScript(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null, "8", keyStr, scriptStr);
                    } else if (keyNodeInfo.length == 6) {
                        keyNodeSaveRet = this.getKeyNodeValueScript(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], "8", keyStr, scriptStr);
                    } else if (keyNodeInfo.length == 9) {
                        keyNodeSaveRet = this.getKeyNodeValueScript(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8],  "8", keyStr, scriptStr);
                    }


                    // 過去ルールからデータを発見
                    if (keyNodeSaveRet[1].equals("true")) {
                        break;
                    }
                }
            }


            // 取得結果確認
            if (keyNodeSaveRet[1].equals("false")) {

                // 取得失敗(データなし)
                retStrs[0] = keyNodeSaveRet[0];
                retStrs[1] = "false";
                retStrs[2] = "";
                
            } else {

                // trueもしくはerrorの可能性あり
                retStrs[0] = keyNodeSaveRet[0];
                retStrs[1] = keyNodeSaveRet[1];
                retStrs[2] = keyNodeSaveRet[2];
            }
        } catch (BatchException be) {
            logger.error("MasterManagerHelper - getKeyValueScript - Error", be);
        } catch (Exception e) {
            logger.error("MasterManagerHelper - getKeyValueScript - Error", e);
            retStrs[0] = "8";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
        }
        //logger.debug("MasterManagerHelper - getKeyValueScript - end");
        return retStrs;
    }


    /**
     * KeyでValueを取得する.<br>
     * Scriptも同時に実行する.<br>
     * Script実行後Valueを更新する可能性もある.<br>
     * 処理フロー.<br>
     * 1.DataDispatcherにKeyを使用してValueの保存先を問い合わせる<br>
     * 2.KeyNodeに接続してValueを取得する<br>
     * 3.結果文字列の配列を作成(成功時は処理番号"8"と"true"とValue、データが存在しない場合は処理番号"8"と"false"とValue、スクリプトエラー時は処理番号"8"と"error"とエラーメッセージ)<br>
     *
     *
     * @param keyStr key値の文字列
     * @param scriptStr 実行Scriptの文字列
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] getKeyValueScriptForUpdate(String keyStr, String scriptStr) throws BatchException {
        //logger.debug("MasterManagerHelper - getKeyValueScriptForUpdate - start");
        String[] retStrs = new String[3];

        String[] keyNodeSaveRet = null;
        String[] keyNodeInfo = null;

        try {

            // Isolation変換実行
            keyStr = this.encodeIsolationConvert(keyStr);

            if (!this.checkKeyLength(keyStr))  {
                // 保存失敗
                retStrs[0] = "9";
                retStrs[1] = "false";
                retStrs[2] = "Key Length Error";
                return retStrs;
            }

            // キー値を使用して取得先を決定
            keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, false);

            // 取得実行
            // Main
            try {
                if (keyNodeInfo.length > 2) 
                    keyNodeSaveRet = this.getKeyNodeValueScript(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null, "8", keyStr, scriptStr);
            } catch (Exception e1) {
                retStrs[0] = "9";
                retStrs[1] = "error";
                retStrs[2] = "MasterNode - Exception";
            }

            // Sub
            try {
                if (keyNodeInfo.length > 5) 
                    keyNodeSaveRet = this.getKeyNodeValueScript(keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], null, null, null, "8", keyStr, scriptStr);
            } catch (Exception e2) {
                retStrs[0] = "9";
                retStrs[1] = "error";
                retStrs[2] = "MasterNode - Exception";
            }


            // Third
            try {
                if (keyNodeInfo.length > 8)
                    keyNodeSaveRet = this.getKeyNodeValueScript(keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], null, null, null,"8", keyStr, scriptStr);
            } catch (Exception e3) {
                retStrs[0] = "9";
                retStrs[1] = "error";
                retStrs[2] = "MasterNode - Exception";
            }


            if (keyNodeSaveRet == null && retStrs != null) return retStrs;

            // 過去に別ルールを設定している場合は過去ルール側でデータ登録が行われている可能性があるの
            // でそちらのルールでのデータ格納場所も調べる
            if (keyNodeSaveRet[1].equals("false")) {

                //System.out.println("過去ルールを探索 - getScript =" + new String(BASE64DecoderStream.decode(keyStr.getBytes())));
                // キー値を使用して取得先を決定
                for (int i = 0; (keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, false, i)) != null; i++) {

                    // 取得実行
                    // Main
                    try {
                        if (keyNodeInfo.length > 2) 
                            keyNodeSaveRet = this.getKeyNodeValueScript(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null, "8", keyStr, scriptStr);
                    } catch (Exception e1) {
                        retStrs[0] = "9";
                        retStrs[1] = "error";
                        retStrs[2] = "MasterNode - Exception";
                    }

                    // Sub
                    try {
                        if (keyNodeInfo.length > 5) 
                            keyNodeSaveRet = this.getKeyNodeValueScript(keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], null, null, null, "8", keyStr, scriptStr);
                    } catch (Exception e2) {
                        retStrs[0] = "9";
                        retStrs[1] = "error";
                        retStrs[2] = "MasterNode - Exception";
                    }


                    // Third
                    try {
                        if (keyNodeInfo.length > 8) 
                            keyNodeSaveRet = this.getKeyNodeValueScript(keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], null, null, null,"8", keyStr, scriptStr);
                    } catch (Exception e3) {
                        retStrs[0] = "9";
                        retStrs[1] = "error";
                        retStrs[2] = "MasterNode - Exception";
                    }

                    if (keyNodeSaveRet != null) {
                        // 過去ルールからデータを発見
                        if (keyNodeSaveRet[1].equals("true")) {
                            break;
                        }
                    }
                }
            }


            if (keyNodeSaveRet == null && retStrs != null) return retStrs;

            // 取得結果確認
            if (keyNodeSaveRet[1].equals("false")) {

                // 取得失敗(データなし)
                retStrs[0] = "9";
                retStrs[1] = "false";
                retStrs[2] = "";
            } else {

                // trueもしくはerrorの可能性あり
                retStrs[0] = "9";
                retStrs[1] = keyNodeSaveRet[1];
                retStrs[2] = keyNodeSaveRet[2];
            }
        } catch (Exception e) {
            logger.error("MasterManagerHelper - getKeyValueScriptForUpdate - Error", e);
            retStrs[0] = "9";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
        }
        //logger.debug("MasterManagerHelper - getKeyValueScriptForUpdate - end");
        return retStrs;
    }


    /**
     * KeyでValueを削除する.<br>
     * 処理フロー.<br>
     * 1.DataDispatcherにKeyを使用してValueの保存先を問い合わせる<br>
     * 2.KeyNodeに接続してValueを削除する<br>
     * 3.結果文字列の配列を作成(成功時は処理番号"5"と"true"とValue、失敗時は処理番号"5"と"false")<br>
     *
     * @param keyStr key値の文字列
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] removeKeyValue(String keyStr, String transactionCode) throws BatchException {
        //logger.debug("MasterManagerHelper - removeKeyValue - start");
        String[] retStrs = new String[3];

        String[] keyNodeRemoveRet = null;
        String[] keyNodeInfo = null;

        String[] oldKeyNodeRemoveRet = null;
        try {

            // Isolation変換実行
            keyStr = this.encodeIsolationConvert(keyStr);

            // Key値チェック
            if (!this.checkKeyLength(keyStr))  {
                retStrs[0] = "5";
                retStrs[1] = "false";
                retStrs[2] = "Key Length Error";
                return retStrs;
            }

            // キー値を使用して取得先を決定
            keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, false);

            // 取得実行
            if (keyNodeInfo.length == 3) {
                keyNodeRemoveRet = this.removeKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null, keyStr, transactionCode);
            } else if (keyNodeInfo.length == 6) {
                keyNodeRemoveRet = this.removeKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyStr, transactionCode);
            } else if (keyNodeInfo.length == 9) {
                keyNodeRemoveRet = this.removeKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], keyStr, transactionCode);
            }


            // 過去に別ルールを設定している場合は過去ルール側でデータ登録が行われている可能性があるので
            // そちらのルールでも削除する
            // キー値を使用して取得先を決定
            for (int i = 0; (keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, false, i)) != null; i++) {

                //System.out.println("過去ルールを探索 - remove =" + new String(BASE64DecoderStream.decode(keyStr.getBytes())));
                // 取得実行
                if (keyNodeInfo.length == 3) {
                    oldKeyNodeRemoveRet = this.removeKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null, keyStr, transactionCode);
                } else if (keyNodeInfo.length == 6) {
                    oldKeyNodeRemoveRet = this.removeKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyStr, transactionCode);
                } else if (keyNodeInfo.length == 9) {
                    oldKeyNodeRemoveRet = this.removeKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], keyStr, transactionCode);
                }

                if (keyNodeRemoveRet == null || keyNodeRemoveRet[1].equals("false")) {
                    if (oldKeyNodeRemoveRet != null && oldKeyNodeRemoveRet[1].equals("true")) {
                        keyNodeRemoveRet = oldKeyNodeRemoveRet;
                    }
                }
            }

            // 取得結果確認
            if (keyNodeRemoveRet == null || keyNodeRemoveRet.length < 1) {
                throw new BatchException("Key Node IO Error: detail info for log file");
            } else if (keyNodeRemoveRet[1].equals("false")) {

                // 削除失敗(元データなし)
                retStrs[0] = keyNodeRemoveRet[0];
                retStrs[1] = "false";
                retStrs[2] = "";
                
            } else {
                retStrs[0] = keyNodeRemoveRet[0];
                retStrs[1] = "true";
                retStrs[2] = keyNodeRemoveRet[2];
            }
        } catch (BatchException be) {
            logger.error("MasterManagerHelper - removeKeyValue - Error", be);
        } catch (Exception e) {
            logger.error("MasterManagerHelper - removeKeyValue - Error", e);
            retStrs[0] = "5";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
        }
        //logger.debug("MasterManagerHelper - removeKeyValue - end");
        return retStrs;
    }



    /**
     * 指定したKeyから指定したTagを取り外す.<br>
     * 処理フロー.<br>
     *
     * @param keyStr key値の文字列
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] removeTargetTagInKey(String tagStr, String keyStr, String transactionCode) throws BatchException {
        //logger.debug("MasterManagerHelper - removeTargetTagInKey - start");
        String[] retStrs = new String[3];

        String[] keyNodeRemoveRet = null;

        String[] oldKeyNodeRemoveRet = null;
        try {

            // Isolation変換実行
            keyStr = this.encodeIsolationConvert(keyStr);

            // Key値チェック
            if (!this.checkKeyLength(keyStr))  {
                retStrs[0] = "40";
                retStrs[1] = "false";
                retStrs[2] = "Key Length Error";
                return retStrs;
            }


            // TagをIsolation変換実行
            tagStr = this.encodeIsolationConvert(tagStr);

            if (!this.checkKeyLength(tagStr))  {
                // 保存失敗
                retStrs[0] = "40";
                retStrs[1] = "false";
                retStrs[2] = "Tag Data Length Error";
            }

            // Tag値保存先を問い合わせ
            String[] tagKeyNodeInfo = DataDispatcher.dispatchKeyNode(tagStr, false);


            // 取得実行
            if (tagKeyNodeInfo.length == 3) {
                keyNodeRemoveRet = this.removeTargetTagInKey(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], null, null, null, tagStr, keyStr, transactionCode);
            } else if (tagKeyNodeInfo.length == 6) {
                keyNodeRemoveRet = this.removeTargetTagInKey(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], tagKeyNodeInfo[3], tagKeyNodeInfo[4], tagKeyNodeInfo[5], tagStr, keyStr, transactionCode);
            } else if (tagKeyNodeInfo.length == 9) {
                keyNodeRemoveRet = this.removeTargetTagInKey(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], tagKeyNodeInfo[3], tagKeyNodeInfo[4], tagKeyNodeInfo[5], tagKeyNodeInfo[6], tagKeyNodeInfo[7], tagKeyNodeInfo[8], tagStr, keyStr, transactionCode);
            }


            // 過去に別ルールを設定している場合は過去ルール側でデータ登録が行われている可能性があるので
            // そちらのルールでも削除する
            // キー値を使用して取得先を決定
            for (int i = 0; (tagKeyNodeInfo = DataDispatcher.dispatchKeyNode(tagStr, false, i)) != null; i++) {

                // 過去ルールを探索
                // 取得実行
                if (tagKeyNodeInfo.length == 3) {
                    oldKeyNodeRemoveRet = this.removeTargetTagInKey(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], null, null, null, tagStr, keyStr, transactionCode);
                } else if (tagKeyNodeInfo.length == 6) {
                    oldKeyNodeRemoveRet = this.removeTargetTagInKey(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], tagKeyNodeInfo[3], tagKeyNodeInfo[4], tagKeyNodeInfo[5], tagStr, keyStr, transactionCode);
                } else if (tagKeyNodeInfo.length == 9) {
                    oldKeyNodeRemoveRet = this.removeTargetTagInKey(tagKeyNodeInfo[0], tagKeyNodeInfo[1], tagKeyNodeInfo[2], tagKeyNodeInfo[3], tagKeyNodeInfo[4], tagKeyNodeInfo[5], tagKeyNodeInfo[6], tagKeyNodeInfo[7], tagKeyNodeInfo[8], tagStr, keyStr, transactionCode);
                }

                if (keyNodeRemoveRet == null || keyNodeRemoveRet[1].equals("false")) {
                    if (oldKeyNodeRemoveRet != null && oldKeyNodeRemoveRet[1].equals("true")) {
                        keyNodeRemoveRet = oldKeyNodeRemoveRet;
                    }
                }
            }

            // 取得結果確認
            if (keyNodeRemoveRet == null || keyNodeRemoveRet.length < 1) {
                throw new BatchException("Key Node IO Error: detail info for log file");
            } else if (keyNodeRemoveRet[1].equals("false")) {

                // 削除失敗(元データなし)
                retStrs[0] = keyNodeRemoveRet[0];
                retStrs[1] = "false";
                retStrs[2] = "";
                
            } else {
                retStrs[0] = keyNodeRemoveRet[0];
                retStrs[1] = "true";
                retStrs[2] = "";
            }
        } catch (BatchException be) {
            logger.error("MasterManagerHelper - removeTargetTagInKey - Error", be);
        } catch (Exception e) {
            logger.error("MasterManagerHelper - removeTargetTagInKey - Error", e);
            retStrs[0] = "40";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
        }
        //logger.debug("MasterManagerHelper - removeTargetTagInKey - end");
        return retStrs;
    }


    /**
     * 既に登録されている値に減算処理を行う.<br>
     * 処理フロー.<br>
     * 1.データノードに減算処理を依頼.<br>
     * 2.正しく処理を完了した場合は加算後の値が帰ってくるのでその値を使用して後続ノードに値を登録<br>
     *
     * @param keyStr key値の文字列
     * @param transactionCode 
     * @param decrValue 減算値
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] decrValue(String keyStr, String transactionCode, String decrValue) throws BatchException {

        //logger.debug("MasterManagerHelper - decrValue - start");
        String[] retStrs = new String[3];

        try {
            // Isolation変換はしない
            // incrValueに処理を移譲しているだけなので
            String decodeData = new String(BASE64DecoderStream.decode(decrValue.getBytes()));
            String minus = "";
            if (decodeData.indexOf("-") != 0) minus = "-";

            retStrs = this.incrValue(keyStr, transactionCode, new String(BASE64EncoderStream.encode((minus + decodeData).getBytes())));

            // 処理番号変更
            retStrs[0] = "14";
        } catch (BatchException be) {

            logger.info("MasterManagerHelper - decrValue - Error", be);
        } catch (Exception e) {

            logger.info("MasterManagerHelper - decrValue - Error", e);
            retStrs[0] = "14";
            retStrs[1] = "false";
            retStrs[2] = "MasterNode - Exception";
        }
        //logger.debug("MasterManagerHelper - decrValue - end");
        return retStrs;
    }


    /**
     * 既に登録されている値に加算処理を行う.<br>
     * 処理フロー.<br>
     * 1.データノードに加算処理を依頼.<br>
     * 2.正しく処理を完了した場合は加算後の値が帰ってくるのでその値を使用して後続ノードに値を登録<br>
     *
     * @param keyStr key値の文字列
     * @param transactionCode 
     * @param incrValue 加算値
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] incrValue(String keyStr, String transactionCode, String incrValue) throws BatchException {

        //logger.debug("MasterManagerHelper - incrValue - start");
        String[] retStrs = new String[3];

        int idx = 0;
        String[] calcRet = null;
        String[] calcFixValue = null;

        try {

            // Isolation変換実行
            keyStr = this.encodeIsolationConvert(keyStr);

            // Key値チェック
            if (!this.checkKeyLength(keyStr))  {
                // 保存失敗
                retStrs[0] = "13";
                retStrs[1] = "false";
                retStrs[2] = "Key Length Error";
                return retStrs;
            }

            // キー値とデータを保存
            // 保存先問い合わせ
            String[] keyNodeInfo = DataDispatcher.dispatchKeyNode(keyStr, false);

            // 成功するまで演算を行う
            for (idx = 0; idx < keyNodeInfo.length; idx=idx+3)  {

                calcRet = null;
                calcRet = this.calcKeyValue(keyNodeInfo[idx], keyNodeInfo[idx + 1], keyNodeInfo[idx + 2], keyStr, incrValue, transactionCode);

                // 1つのノードで演算に成功した場合はそこでbreak
                if (calcRet != null && calcRet[1].equals("true")) {

                    calcFixValue = new String[2];
                    calcFixValue[0] = keyStr;
                    calcFixValue[1] = calcRet[2];
                    break;
                } else if (calcRet != null && calcRet[1].equals("false")){
                    // 論理的に失敗した場合は即break
                    calcFixValue = null;
                    break;
                }
            }


            // 演算結果を残りのノードへ保存
            try {
                if (calcFixValue != null)  {
                    String[] keyNodeSaveRet = null;
                    if (keyNodeInfo.length == 6 && idx < 3) {
                        keyNodeSaveRet = this.setKeyNodeValue(keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], null, null, null, "1", calcFixValue, transactionCode);
                    }

                    if (keyNodeInfo.length == 9 && idx < 3) {
                        keyNodeSaveRet = this.setKeyNodeValue(keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], "1", calcFixValue, transactionCode);
                    } else if (keyNodeInfo.length == 9 && idx < 6) {
                        keyNodeSaveRet = this.setKeyNodeValue(keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], null, null, null, "1", calcFixValue, transactionCode);
                    }
                }
            } catch (Exception e) {
                // 無視
            }

            // 保存結果確認はしない
            if (calcFixValue != null)  {
                // 保存失敗
                retStrs[0] = "13";
                retStrs[1] = "true";
                retStrs[2] = calcRet[2];

            } else {

                retStrs[0] = "13";
                retStrs[1] = "false";
                retStrs[2] = "NG";
            }
        } catch (BatchException be) {
            logger.info("MasterManagerHelper - incrValue - Error", be);
        } catch (Exception e) {
            logger.info("MasterManagerHelper - incrValue - Error", e);
            retStrs[0] = "13";
            retStrs[1] = "false";
            retStrs[2] = "MasterNode - Exception";
        }
        //logger.debug("MasterManagerHelper - incrValue - end");
        return retStrs;
    }


    /**
     * Key値を指定してデータのロックを取得する.<br>
     * 処理フロー.<br>
     * 1.DataDispatcherに依頼してKeyの保存先を問い合わせる.<br>
     * 2.取得した保存先にロックを依頼.<br>
     * 3.全てのロックが完了した時点で終了.<br>
     * 4.結果文字列の配列を作成(成功時は処理番号"30"と"true"と"ロック番号"、失敗時は処理番号"30"と"false")<br>
     *
     * @param keyStrs key値
     * @param transactionCode ロック時のTransactionCode
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] lockingData(String keyStr, String transactionCode, String lockingTime, String lockingWaitTime) throws BatchException {
        //logger.debug("MasterManagerHelper - lockingData - start");

        String[] retStrs = new String[3];

        String[] keyNodeLockRet = null;

        try {

            // Isolation変換実行
            keyStr = this.encodeIsolationConvert(keyStr);

            if (!transactionMode) {
                retStrs = new String[2];
                retStrs[0] = "30";
                retStrs[1] = "false";
                return retStrs;
            }

            // Key値チェック
            if (!this.checkKeyLength(keyStr))  {
                retStrs[0] = "30";
                retStrs[1] = "false";
                retStrs[2] = "Key Length Error";
                return retStrs;
            }

            // TransactionManagerに処理を依頼
            keyNodeLockRet = lockKeyNodeValue(transactionManagerInfo[0], transactionManagerInfo[1], keyStr, transactionCode, lockingTime, lockingWaitTime);


            // 取得結果確認
            if (keyNodeLockRet[1].equals("false")) {

                // Lock失敗
                retStrs[0] = keyNodeLockRet[0];
                retStrs[1] = "false";
                retStrs[2] = "";
                
            } else {

                // Lock成功
                retStrs[0] = keyNodeLockRet[0];
                retStrs[1] = "true";
                retStrs[2] = keyNodeLockRet[2];
            }
        } catch (BatchException be) {
            logger.error("MasterManagerHelper - lockingData - Error", be);
        } catch (Exception e) {
            retStrs[0] = "30";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
        }
        //logger.debug("MasterManagerHelper - lockingData - end");;
        return retStrs;
    }


    /**
     * Key値を指定してデータのロックを解除する.<br>
     * 処理フロー.<br>
     * 2.DataDispatcherに依頼してKeyの保存先を問い合わせる.<br>
     * 3.取得した保存先にロックを依頼.<br>
     * 4.全てのロックが完了した時点で終了.<br>
     * 5.結果文字列の配列を作成(成功時は処理番号"31"と"true"と"ロック番号"、失敗時は処理番号"31"と"false")<br>
     *
     * @param keyStr key値の文字列
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] releaseLockingData(String keyStr, String transactionCode) throws BatchException {
        //logger.debug("MasterManagerHelper - releaseLockingData - start");
        String[] retStrs = new String[3];

        String[] keyNodeReleaseRet = null;

        try {

            // Isolation変換実行
            keyStr = this.encodeIsolationConvert(keyStr);

            if (!transactionMode) {
                retStrs = new String[2];
                retStrs[0] = "31";
                retStrs[1] = "false";
                return retStrs;
            }

            // Key値チェック
            if (!this.checkKeyLength(keyStr))  {
                retStrs[0] = "31";
                retStrs[1] = "false";
                retStrs[2] = "Key Length Error";
                return retStrs;
            }

            // TransactionManagerに処理を依頼
            keyNodeReleaseRet = releaseLockKeyNodeValue(transactionManagerInfo[0], transactionManagerInfo[1], keyStr, transactionCode);


            // リリース結果確認
            if (keyNodeReleaseRet[1].equals("false")) {

                // リリース失敗
                retStrs[0] = "31";
                retStrs[1] = "false";
                retStrs[2] = "";
            } else {

                // リリース成功
                retStrs[0] = "31";
                retStrs[1] = "true";
                retStrs[2] = keyNodeReleaseRet[2];
            }
        } catch (BatchException be) {
            logger.error("MasterManagerHelper - releaseLockingData - Error", be);
        } catch (Exception e) {
            retStrs[0] = "31";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
        }
        //logger.debug("MasterManagerHelper - releaseLockingData - end");
        return retStrs;
    }


    /**
     * Transactionを開始する.<br>
     *
     * @return String[] 結果 配列の3つめがTransactionCode
     * @throws BatchException
     */
    private String[] startTransaction() throws BatchException {
        //logger.debug("MasterManagerHelper - removeKeyValue - start");
        String[] retStrs = new String[3];
        if (!transactionMode) {
            retStrs[0] = "37";
            retStrs[1] = "false";
            retStrs[2] = "No Transaction Mode";
            return retStrs;
        }

        retStrs[0] = "37";
        retStrs[1] = "true";
        retStrs[2] = ((Long)System.nanoTime()).toString() + new Integer(this.hashCode()).toString();
        //logger.debug("MasterManagerHelper - removeKeyValue - end");
        return retStrs;
    }


    /**
     * TagでKey値群を取得する.<br>
     * 処理フロー.<br>
     * 1.DataDispatcherにTagを使用してKey値群の保存先を問い合わせる<br>
     * 2.KeyNodeに接続してKey値群を取得する<br>
     * 3.結果文字列の配列を作成(成功時は処理番号"2"と"true"とKey値群、失敗時は処理番号"2"と"false"とKey値群)<br>
     *
     * @param tagStr tag値の文字列
     * @param noExistsData 存在していないデータを取得するかの指定(true:取得する false:取得しない)
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] getTagKeys(String tagStr, boolean noExistsData) throws BatchException {
        //logger.debug("MasterManagerHelper - getTagKeys - start");
        String[] retStrs = new String[3];

        String[] keyNodeSaveRet = null;

        try {
            // Isolation変換実行
            tagStr = this.encodeIsolationConvert(tagStr);

            // Key値チェック
            if (!this.checkKeyLength(tagStr))  {
                retStrs[0] = "4";
                retStrs[1] = "false";
                retStrs[2] = "Tag Length Error";
                return retStrs;
            }

            // キー値を使用して取得先を決定
            String[] keyNodeInfo = DataDispatcher.dispatchKeyNode(tagStr, false);


            // 取得実行
            if (keyNodeInfo.length == 3) {
                keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null, "4", tagStr);
            } else if (keyNodeInfo.length == 6) {
                keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], "4", tagStr);
            } else if (keyNodeInfo.length == 9) {
                keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], "4", tagStr);
            }


            // 過去に別ルールを設定している場合は過去ルール側でデータ登録が行われている可能性があるので
            // そちらのルールでのデータ格納場所も調べる
            if (keyNodeSaveRet[1].equals("false")) {

                //System.out.println("過去ルールを探索 - getTagKeys(" + keyNodeInfo[2] + ") =" + new String(BASE64DecoderStream.decode(tagStr.getBytes())));
                for (int i = 0; (keyNodeInfo = DataDispatcher.dispatchKeyNode(tagStr, this.reverseAccess, i)) != null; i++) {

                    // キー値を使用して取得先を決定
                    // 取得実行
                    if (keyNodeInfo.length == 3) {
                        keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], null, null, null, "4", tagStr);
                    } else if (keyNodeInfo.length == 6) {
                        keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], "4", tagStr);
                    } else if (keyNodeInfo.length == 9) {
                        keyNodeSaveRet = this.getKeyNodeValue(keyNodeInfo[0], keyNodeInfo[1], keyNodeInfo[2], keyNodeInfo[3], keyNodeInfo[4], keyNodeInfo[5], keyNodeInfo[6], keyNodeInfo[7], keyNodeInfo[8], "4", tagStr);
                    }

                    if (keyNodeSaveRet[1].equals("true")) break;
                }
            }

            // 取得結果確認
            if (keyNodeSaveRet[1].equals("false")) {

                // 取得失敗(データなし)
                retStrs[0] = keyNodeSaveRet[0];
                retStrs[1] = "false";
                retStrs[2] = "";
            } else {

                // データ有り
                if (noExistsData) {

                    retStrs[0] = keyNodeSaveRet[0];
                    retStrs[1] = "true";

                    if (!this.isolationMode) {

                        retStrs[2] = keyNodeSaveRet[2];
                    } else {

                        String[] splitList = keyNodeSaveRet[2].split(ImdstDefine.imdstTagKeyAppendSep);
                        if (splitList.length > 0) {

                            StringBuilder retBuf = new StringBuilder(ImdstDefine.stringBufferLargeSize);
                            String retSep = "";

                            for (int idx = 0; idx < splitList.length; idx++) {

                                retBuf.append(retSep);
                                retBuf.append(this.decodeIsolationConvert(splitList[idx]));
                                retSep = ImdstDefine.imdstTagKeyAppendSep;
                            }

                            retStrs[2] = retBuf.toString();
                        }
                    }
                } else {

                    retStrs[0] = keyNodeSaveRet[0];
                    retStrs[1] = "true";

                    String[] splitList = keyNodeSaveRet[2].split(ImdstDefine.imdstTagKeyAppendSep);
                    keyNodeSaveRet[2] = null;

                    if (splitList.length > 0) {

                        StringBuilder retBuf = new StringBuilder(ImdstDefine.stringBufferLargeSize);
                        String retSep = "";

                        for (int idx = 0; idx < splitList.length; idx++) {

                            String decodeIsokationCnvKey = this.decodeIsolationConvert(splitList[idx]);
                            String[] retKey = this.getKeyValue(decodeIsokationCnvKey);
                            if (retKey[1].equals("true")) {

                                retBuf.append(retSep);
                                retBuf.append(decodeIsokationCnvKey);
                                retSep = ImdstDefine.imdstTagKeyAppendSep;
                            }
                        }

                        retStrs[2] = retBuf.toString();
                        if (retStrs[2].length() == 0) {

                            retStrs[1] = "false";
                        }
                    } else {

                        retStrs[1] = "false";
                        retStrs[2] = "";
                    }
                }

            }
        } catch (BatchException be) {
            logger.error("MasterManagerHelper - getTagKeys - Error", be);
            retStrs[0] = "4";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
        } catch (Exception e) {
            logger.error("MasterManagerHelper - getTagKeys - Exception", e);
            retStrs[0] = "4";
            retStrs[1] = "error";
            retStrs[2] = "MasterNode - Exception";
        }

        //logger.debug("MasterManagerHelper - getTagKeys - end");
        return retStrs;
    }


    /**
     * KeyNodeからデータを取得する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(2=Keyでデータを取得, 4=TagでKey値を返す)
     * @param key Key値
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] getKeyNodeValue(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String type, String key) throws BatchException {
        return getKeyNodeValue(keyNodeName, keyNodePort, keyNodeFullName, subKeyNodeName, subKeyNodePort, subKeyNodeFullName, type, key, false);
    }


    /**
     * KeyNodeからデータを取得する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(2=Keyでデータを取得, 4=TagでKey値を返す)
     * @param key Key値
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] getKeyNodeValue(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String thirdKeyNodeName, String thirdKeyNodePort, String thirdKeyNodeFullName, String type, String key) throws BatchException {
        return getKeyNodeValue(keyNodeName, keyNodePort, keyNodeFullName, subKeyNodeName, subKeyNodePort, subKeyNodeFullName, thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, type, key, false);
    }


    /**
     * KeyNodeからデータを取得する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(2=Keyでデータを取得, 4=TagでKey値を返す)
     * @param key Key値
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] getKeyNodeValue(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String thirdKeyNodeName, String thirdKeyNodePort, String thirdKeyNodeFullName, String type, String key, boolean returnVersion) throws BatchException {
        boolean exceptionFlg = false;
        String[] ret = null;
        String[] thirdRet = null;
        BatchException retBe = null;

        try {

            ret = this.getKeyNodeValue(keyNodeName, keyNodePort, keyNodeFullName, subKeyNodeName, subKeyNodePort, subKeyNodeFullName, type, key, returnVersion);

        } catch (BatchException be) {

            retBe = be;
            exceptionFlg = true;
        } catch (Exception e) {

            retBe = new BatchException(e);
            exceptionFlg = true;
        } finally {
            
            try {

                if (exceptionFlg) {

                    thirdRet = this.getKeyNodeValue(thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, null, null, null, type, key, returnVersion);
                    ret = thirdRet;
                } else {
                    // サードノードの使用終了のみマーク
                    if (thirdKeyNodeFullName != null) 
                        super.execNodeUseEnd(thirdKeyNodeFullName);
                }
            } catch (Exception e) {

                if (exceptionFlg) throw retBe;
            }
        }
        return ret;
    }


    /**
     * KeyNodeからデータを取得する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(2=Keyでデータを取得, 4=TagでKey値を返す)
     * @param key Key値
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] getKeyNodeValue(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String type, String key, boolean returnVersion) throws BatchException {
        KeyNodeConnector keyNodeConnector = null;

        String[] retParams = null;
        String[] cnvConsistencyRet = null;

        boolean slaveUse = false;
        boolean mainRetry = false;
        int nowUse = 0;

        String[] mainNodeRetParam = null;
        String[] subNodeRetParam = null;

        String nowUseNodeInfo = null;

        SocketException se = null;
        IOException ie = null;

        try {

            // KeyNodeとの接続を確立
            keyNodeConnector = this.createKeyNodeConnection(keyNodeName, keyNodePort, keyNodeFullName, false);
            nowUse = 1;
            while (true) {

                // 戻り値がnullの場合は何だかの理由で接続に失敗しているのでスレーブの設定がある場合は接続する
                // スレーブの設定がない場合は、エラーとしてExceptionをthrowする
                if (keyNodeConnector == null) {
                    if (subKeyNodeName != null) keyNodeConnector = this.createKeyNodeConnection(subKeyNodeName, subKeyNodePort, subKeyNodeFullName, false);

                    if (keyNodeConnector == null) {

                        if (mainNodeRetParam != null) break;
                        throw new BatchException("Key Node IO Error: detail info for log file");
                    }
                    slaveUse = true;
                    nowUse = 2;
                }

                // ノード取得処理
                try {

                    // 文字列Buffer初期化
                    this.getSendData.delete(0, Integer.MAX_VALUE);
                    String sendStr = null;

                    // 処理種別判別
                    if (type.equals("2")) {

                        // Key値でValueを取得
                        // パラメータ作成 処理タイプ[セパレータ]キー値
                        // 送信
                        this.getSendData.append(type);
                        this.getSendData.append(ImdstDefine.keyHelperClientParamSep);
                        this.getSendData.append(this.stringCnv(key));
                        sendStr = this.getSendData.toString();

                        keyNodeConnector.println(sendStr);
                        keyNodeConnector.flush();

                        // 返却値取得
                        String retParam = keyNodeConnector.readLine(sendStr);
                        // 返却値を分解
                        // 処理番号, true or false, valueの想定
                        // value値にセパレータが入っていても無視する

                        retParams = retParam.split(ImdstDefine.keyHelperClientParamSep, 3);
                    } else if (type.equals("4")) {

                        // Tag値でキー値群を取得
                        // パラメータ作成 処理タイプ[セパレータ]キー値

                        // 送信
                        this.getSendData.append(type);
                        this.getSendData.append(ImdstDefine.keyHelperClientParamSep);
                        this.getSendData.append(this.stringCnv(key));
                        sendStr = this.getSendData.toString();
                        keyNodeConnector.println(this.getSendData.toString());
                        keyNodeConnector.flush();

                        // 返却値取得
                        String retParam = keyNodeConnector.readLine(sendStr);

                        // 返却値を分解
                        // 処理番号, true or false, valueの想定
                        // value値にセパレータが入っていても無視する

                        retParams = retParam.split(ImdstDefine.keyHelperClientParamSep, 3);
                    }

                    // 使用済みの接続を戻す
                    super.addKeyNodeCacheConnectionPool(keyNodeConnector);

                    // Tag取得の場合は値が取れ次第終了
                    if (type.equals("4")) {
                        if (retParams != null && retParams.length > 1 && retParams[1].equals("true")) {
                            cnvConsistencyRet = dataConvert4Consistency(retParams[2]);

                            // Valueのバージョンをクライアントに返す場合は処理
                            retParams[2] = cnvConsistencyRet[0];
                            if (returnVersion) {
                                String[] workRet = new String[retParams.length + 1];
                                for (int idx = 0; idx < retParams.length; idx++) {
                                    workRet[idx] = retParams[idx];
                                }
                                workRet[retParams.length] = cnvConsistencyRet[1];
                            } else {

                                retParams[2] = cnvConsistencyRet[0];
                            }
                        }
                        break;
                    }

                    // 一貫性のモードに合わせて処理を分岐
                    if (this.dataConsistencyMode < 2) {

                        // 弱一貫性 or 中一貫性の場合はデータが取れ次第返却
                        // 一貫性データが付随した状態から通常データに変換する
                        if (retParams != null && retParams.length > 1 && retParams[1].equals("true")) {

                            cnvConsistencyRet = dataConvert4Consistency(retParams[2]);
                            retParams[2] = cnvConsistencyRet[0];
                            if (returnVersion) {
                                String[] workRet = new String[retParams.length + 1];
                                for (int idx = 0; idx < retParams.length; idx++) {
                                    workRet[idx] = retParams[idx];
                                }
                                workRet[retParams.length] = cnvConsistencyRet[1];
                                retParams = workRet;
                            } else {

                                retParams[2] = cnvConsistencyRet[0];
                            }
                        }
                        break;
                    } else {

                        // 強一貫性の場合は両方のデータの状態を確かめる
                        if (nowUse == 1) mainNodeRetParam = retParams;
                        if (nowUse == 2) subNodeRetParam = retParams;

                        if (subKeyNodeName == null) break;
                        if (mainNodeRetParam != null && subNodeRetParam != null) break;
                    }

                } catch(SocketException tSe) {
                    //tSe.printStackTrace();
                    // ここでのエラーは通信中に発生しているので、スレーブノードを使用していない場合のみ再度スレーブへの接続を試みる
                    se = tSe;
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                } catch(IOException tIe) {
                    //tIe.printStackTrace();
                    // ここでのエラーは通信中に発生しているので、スレーブノードを使用していない場合のみ再度スレーブへの接続を試みる
                    ie = tIe;
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                }

                // 既にスレーブの接続を使用している場合は、もう一度だけメインノードに接続を試みる
                // それで駄目な場合はエラーとする
                if (slaveUse) {
                    if (mainRetry) {

                        if (mainNodeRetParam != null || subNodeRetParam != null) break;

                        if (se != null) throw se;
                        if (ie != null) throw ie;
                        throw new BatchException("Key Node IO Error: detail info for log file");
                    } else {

                        // メインKeyNodeとの接続を確立
                        keyNodeConnector = this.createKeyNodeConnection(keyNodeName, keyNodePort, keyNodeFullName, true);
                        if (keyNodeConnector == null) {
                            if (mainNodeRetParam != null || subNodeRetParam != null) break;
                            throw new BatchException("Key Node IO Error: detail info for log file");
                        }
                        mainRetry = true;
                        nowUse = 1;
                    }
                } else {
                    if (subKeyNodeName == null) {
                        if (mainNodeRetParam != null) break;
                        if (se != null) throw se;
                        if (ie != null) throw ie;
                    } else{
                        keyNodeConnector = null;
                    }
                }
            }

            // 強一貫性且つKey－Value取得の場合は処理
            if (type.equals("2") && this.dataConsistencyMode == 2) {
                retParams = this.strongConsistencyDataConvert(retParams, mainNodeRetParam, subNodeRetParam, type, returnVersion);
            }
        } catch (Exception e) {
            //e.printStackTrace();
            logger.error("PollQueue =[" + this.myPollQueue + "] RequestTime=[" + new Date() + "] ErrorKey=[" + new String(BASE64DecoderStream.decode(this.decodeIsolationConvert(key).getBytes())) + "]");

            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            throw new BatchException(e);
        } finally {
            this.getSendData.delete(0, Integer.MAX_VALUE);
            // ノードの使用終了をマーク
            super.execNodeUseEnd(keyNodeFullName);

            if (subKeyNodeName != null) 
                super.execNodeUseEnd(subKeyNodeFullName);
        }
        return retParams;
    }



    /**
     * KeyNodeからデータを更新し、取得する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(8=Keyでデータを取得)
     * @param scriptStr Script文字列
     * @param key Key値
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] getKeyNodeValueScript(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String thirdKeyNodeName, String thirdKeyNodePort, String thirdKeyNodeFullName, String type, String key, String scriptStr) throws BatchException {
        boolean exceptionFlg = false;
        String[] ret = null;
        String[] thirdRet = null;
        BatchException retBe = null;

        try {

            ret = this.getKeyNodeValueScript(keyNodeName, keyNodePort, keyNodeFullName, subKeyNodeName, subKeyNodePort, subKeyNodeFullName, type, key, scriptStr);
        } catch (BatchException be) {

            retBe = be;
            exceptionFlg = true;
        } catch (Exception e) {

            retBe = new BatchException(e);
            exceptionFlg = true;
        } finally {
            
            try {
                if (exceptionFlg) {
                    thirdRet = this.getKeyNodeValueScript(thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, null, null, null, type, key, scriptStr);
                    ret = thirdRet;
                } else {
                    // サードノードの使用終了のみマーク
                    if (thirdKeyNodeFullName != null) 
                        super.execNodeUseEnd(thirdKeyNodeFullName);
                }
            } catch (Exception e) {
                if (exceptionFlg) throw retBe;
            }
        }

        return ret;
    }


    /**
     * KeyNodeからデータを取得する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(8=Keyでデータを取得)
     * @param scriptStr Script文字列
     * @param key Key値
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] getKeyNodeValueScript(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String type, String key, String scriptStr) throws BatchException {
        KeyNodeConnector keyNodeConnector = null;

        String[] retParams = null;
        String[] cnvConsistencyRet = null;

        boolean slaveUse = false;
        boolean mainRetry = false;

        String nowUseNodeInfo = null;


        SocketException se = null;
        IOException ie = null;
        try {

            // KeyNodeとの接続を確立
            keyNodeConnector = this.createKeyNodeConnection(keyNodeName, keyNodePort, keyNodeFullName, false);

            while (true) {

                // 戻り値がnullの場合は何だかの理由で接続に失敗しているのでスレーブの設定がある場合は接続する
                // スレーブの設定がない場合は、エラーとしてExceptionをthrowする
                if (keyNodeConnector == null) {
                    if (subKeyNodeName != null) keyNodeConnector = this.createKeyNodeConnection(subKeyNodeName, subKeyNodePort, subKeyNodeFullName, false);

                    if (keyNodeConnector == null) throw new BatchException("Key Node IO Error: detail info for log file");
                    slaveUse = true;
                }


                try {
                    // 処理種別判別
                    if (type.equals("8")) {

                        // Key値でValueを取得
                        StringBuilder buf = new StringBuilder(ImdstDefine.stringBufferMiddleSize);
                        String sendStr = null;
                        // パラメータ作成 処理タイプ[セパレータ]キー値のハッシュ値文字列
                        buf.append("8");
                        buf.append(ImdstDefine.keyHelperClientParamSep);
                        buf.append(this.stringCnv(key));
                        buf.append(ImdstDefine.keyHelperClientParamSep);
                        buf.append(scriptStr);
                        sendStr = buf.toString();

                        // 送信
                        keyNodeConnector.println(buf.toString());
                        keyNodeConnector.flush();

                        // 返却値取得
                        String retParam = keyNodeConnector.readLine(sendStr);

                        // 返却値を分解
                        // 処理番号, true or false, valueの想定
                        // value値にセパレータが入っていても無視する
                        retParams = retParam.split(ImdstDefine.keyHelperClientParamSep, 3);
                    } 

                    // 使用済みの接続を戻す
                    super.addKeyNodeCacheConnectionPool(keyNodeConnector);

                    // 一貫性のモードに合わせて処理を分岐
                    if (this.dataConsistencyMode == 0) {

                        // 弱一貫性の場合はデータが取れ次第返却
                        if (retParams != null && retParams.length > 1 && retParams[1].equals("true")) {

                            // 一貫性データが付随した状態から通常データに変換する
                            // 弱一貫性の場合は時間は使用しない
                            cnvConsistencyRet = dataConvert4Consistency(retParams[2]);
                            retParams[2] = cnvConsistencyRet[0];
                        }
                        break;
                    } else {

                        
                        // 強一貫性の場合は両方のデータの状態を確かめる

                        // 弱一貫性の場合はデータが取れ次第返却
                        if (retParams != null && retParams.length > 1 && retParams[1].equals("true")) {

                            // 一貫性データが付随した状態から通常データに変換する
                            // 弱一貫性の場合は時間は使用しない
                            cnvConsistencyRet = dataConvert4Consistency(retParams[2]);
                            retParams[2] = cnvConsistencyRet[0];
                        }
                        break;
                    }

                } catch(SocketException tSe) {
                    // ここでのエラーは通信中に発生しているので、スレーブノードを使用していない場合のみ再度スレーブへの接続を試みる
                    se = tSe;
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                } catch(IOException tIe) {
                    // ここでのエラーは通信中に発生しているので、スレーブノードを使用していない場合のみ再度スレーブへの接続を試みる
                    ie = tIe;
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                }

                // 既にスレーブの接続を使用している場合は、もう一度だけメインノードに接続を試みる
                // それで駄目な場合はエラーとする
                if (slaveUse) {
                    if (mainRetry) {
                        if (se != null) throw se;
                        if (ie != null) throw ie;
                        throw new BatchException("Key Node IO Error: detail info for log file");
                    } else {

                        // メインKeyNodeとの接続を確立
                        keyNodeConnector = this.createKeyNodeConnection(keyNodeName, keyNodePort, keyNodeFullName, true);
                        if (keyNodeConnector == null) throw new BatchException("Key Node IO Error: detail info for log file");
                        mainRetry = true;
                    }
                } else {
                    if (subKeyNodeName == null) {
                        if (se != null) throw se;
                        if (ie != null) throw ie;
                    } else{
                        keyNodeConnector = null;
                    }
                }
            }
        } catch (Exception e) {
            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            throw new BatchException(e);
        } finally {
            // ノードの使用終了をマーク
            super.execNodeUseEnd(keyNodeFullName);

            if (subKeyNodeName != null) 
                super.execNodeUseEnd(subKeyNodeFullName);
        }
        return retParams;
    }



    /**
     * KeyNodeに対してデータを保存する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param thirdKeyNodeName サードデータノードの名前(IPなど)
     * @param thirdKeyNodePort サードデータノードのアクセスポート番号
     * @param type 処理タイプ(1=Keyとデータノード設定, 3=Tagにキーを追加, 30=ロックを取得, 31=ロックを解除)
     * @param values 送信データ
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] setKeyNodeValue(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String thirdKeyNodeName, String thirdKeyNodePort, String thirdKeyNodeFullName, String type, String[] values, String transactionCode) throws BatchException {
        return setKeyNodeValue(keyNodeName, keyNodePort, keyNodeFullName, subKeyNodeName, subKeyNodePort,  subKeyNodeFullName, thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, type, values, transactionCode, false);
    }

    /**
     * KeyNodeに対してデータを保存する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param thirdKeyNodeName サードデータノードの名前(IPなど)
     * @param thirdKeyNodePort サードデータノードのアクセスポート番号
     * @param type 処理タイプ(1=Keyとデータノード設定, 3=Tagにキーを追加, 30=ロックを取得, 31=ロックを解除)
     * @param values 送信データ
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] setKeyNodeValue(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String thirdKeyNodeName, String thirdKeyNodePort, String thirdKeyNodeFullName, String type, String[] values, String transactionCode, boolean delayFlg) throws BatchException {
        boolean exceptionFlg = false;
        String[] ret = null;
        String[] thirdRet = null;
        BatchException retBe = null;

        try {
            ret = this.setKeyNodeValue(keyNodeName, keyNodePort, keyNodeFullName, subKeyNodeName, subKeyNodePort, subKeyNodeFullName, type, values, transactionCode, delayFlg);
        } catch (BatchException be) {

            retBe = be;
            exceptionFlg = true;
        } catch (Exception e) {

            retBe = new BatchException(e);
            exceptionFlg = true;
        } finally {

            try {
                // 遅延書き込みに合わせて処理
                if (!delayFlg) {
                    // 遅延させない
                    thirdRet = this.setKeyNodeValue(thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, null, null, null, type, values, transactionCode);
                } else {
                    // 遅延指定だが、Exceptionの発生状況に合わせて処理
                    if (exceptionFlg) {
                        // Exceptionが出ているので遅延させない
                        thirdRet = this.setKeyNodeValue(thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, null, null, null, type, values, transactionCode);
                    } else {

                        // 自身よりも前のノードが保存に成功しているので、遅延させる
                        thirdRet = this.setKeyNodeValue(thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, null, null, null, type, values, transactionCode, true);
                    }
                }
                if (exceptionFlg) ret = thirdRet;
            } catch (Exception e) {
                if (exceptionFlg) throw retBe;
            }
        }

        
        return ret;
    }


    /**
     * KeyNodeに対してデータを保存する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(1=Keyとデータノード設定, 3=Tagにキーを追加, 30=ロックを取得, 31=ロックを解除)
     * @param values 送信データ
     * @param delayFlg　遅延書き込み指定
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] setKeyNodeValue(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String type, String[] values, String transactionCode) throws BatchException {
        return setKeyNodeValue(keyNodeName, keyNodePort, keyNodeFullName, subKeyNodeName, subKeyNodePort, subKeyNodeFullName, type, values, transactionCode, false);
    }



    /**
     * KeyNodeに対してデータを保存する.<br>
     * 遅延指定あり.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(1=Keyとデータノード設定, 3=Tagにキーを追加, 30=ロックを取得, 31=ロックを解除)
     * @param values 送信データ
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] setKeyNodeValue(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String type, String[] values, String transactionCode, boolean delayFlg) throws BatchException {
        KeyNodeConnector keyNodeConnector = null;
        KeyNodeConnector slaveKeyNodeConnector = null;


        String nodeName = keyNodeName;
        String nodePort = keyNodePort;
        String nodeFullName = keyNodeFullName;

        String[] retParams = null;

        int counter = 0;

        String tmpSaveHost = null;
        String[] tmpSaveData = null;
        String retParam = null;

        boolean mainNodeSave = false;
        boolean subNodeSave = false;

        boolean slaveSendEnd = false;

        // 文字列Buffer
        this.setSendData.delete(0, Integer.MAX_VALUE);
        String sendStr = null;
        boolean subNodeConnect = false;

        try {

            // TransactionModeの状態に合わせてLock状態を確かめる
            if (transactionMode) {
                while (true) {

                    // TransactionMode時
                    // TransactionManagerに処理を依頼
                    String[] keyNodeLockRet = hasLockKeyNode(transactionManagerInfo[0], transactionManagerInfo[1], values[0]);

                    // 取得結果確認
                    if (keyNodeLockRet[1].equals("true")) {

                        if (keyNodeLockRet[2].equals(transactionCode)) break;
                    } else {
                        break;
                    }
                }
            }

            // 送信パラメータ作成 キー値のハッシュ値文字列[セパレータ]データノード名
            this.setSendData.append(type);
            this.setSendData.append(ImdstDefine.keyHelperClientParamSep);
            this.setSendData.append(this.stringCnv(values[0]));               // Key値
            this.setSendData.append(ImdstDefine.keyHelperClientParamSep);
            this.setSendData.append(transactionCode);                         // Transaction値
            this.setSendData.append(ImdstDefine.keyHelperClientParamSep);
            this.setSendData.append(values[1]);                               // Value値
            this.setSendData.append(ImdstDefine.setTimeParamSep);
            this.setSendData.append(setTime);                                 // 保存バージョン
            sendStr = this.setSendData.toString();

            // KeyNodeとの接続を確立
            keyNodeConnector = this.createKeyNodeConnection(nodeName, nodePort, nodeFullName, false);


            // DataNodeに送信
            if (keyNodeConnector != null) {
                // 接続結果と、現在の保存先状況で処理を分岐
                try {

                    // 送信
                    // 遅延指定確認
                    // Subが指定だれていてdelay指定がtrueの場合
                    if (!delayFlg) {

                        // 同期
                        keyNodeConnector.println(sendStr);
                        keyNodeConnector.flush();

                        // スレーブノードが存在する場合は送信まで済ませておく
                        if (subKeyNodeName != null) {

                            // SubDataNodeに送信
                            slaveKeyNodeConnector = this.createKeyNodeConnection(subKeyNodeName, subKeyNodePort, subKeyNodeFullName, false);
                            if (slaveKeyNodeConnector != null) {
                                    // 送信
                                    slaveKeyNodeConnector.println(sendStr);
                                    slaveKeyNodeConnector.flush();
                                    slaveSendEnd = true;
                            }
                        }

                        // 返却値取得
                        retParam = keyNodeConnector.readLine(sendStr);

                        // 処理種別判別
                        if (type.equals("1")) {

                            // Key値でValueを保存
                            // splitは遅いので特定文字列で返却値が始まるかをチェックし始まる場合は登録成功
                            if (retParam != null && retParam.indexOf(ImdstDefine.keyNodeKeyRegistSuccessStr) == 0) {

                                mainNodeSave = true;
                            } else {

                                // 論理的に登録失敗
                                super.setDeadNode(nodeName + ":" + nodePort, 3, null);
                                logger.error("setKeyNodeValue Logical Error Node =["  + nodeName + ":" + nodePort + "] retParam=[" + retParam + "]" + " Connectoer=[" + keyNodeConnector.connectorDump() + "]");
                            }
                        } else if (type.equals("3")) {

                            // Tag値でキー値を保存

                            // splitは遅いので特定文字列で返却値が始まるかをチェックし始まる場合は登録成功
                            if (retParam != null && retParam.indexOf(ImdstDefine.keyNodeTagRegistSuccessStr) == 0) {
                                mainNodeSave = true;
                            } else {
                                // 論理的に登録失敗
                                super.setDeadNode(nodeName + ":" + nodePort, 4, null);
                                logger.error("setKeyNodeValue Logical Error Node =["  + nodeName + ":" + nodePort + "] retParam=[" + retParam + "]" + " Connectoer=[" + keyNodeConnector.connectorDump() + "]");
                            }
                        }
                    } else {

                        // 遅延
                        // スレーブノード接続処理だけ終わらしておく処理
                        if (subKeyNodeName != null) {
                            // SubDataNodeに送信
                            slaveKeyNodeConnector = this.createKeyNodeConnection(subKeyNodeName, subKeyNodePort, subKeyNodeFullName, false);
                        }

                        // 遅延の場合は結果が送られてこないのでNetworkから読みださない
                        keyNodeConnector.print("-");
                        keyNodeConnector.println(sendStr);
                        keyNodeConnector.flush();
                        mainNodeSave = true;
                        // 結果を作りだす
                        if (type.equals("1")) {
                            retParam = "1,true,OK";
                        } else if (type.equals("3")) {
                            retParam = "3,true,OK";
                        }
                    }

                    // 使用済みの接続を戻す
                    super.addKeyNodeCacheConnectionPool(keyNodeConnector);
                } catch (SocketException se) {

                    //se.printStackTrace();
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                    super.setDeadNode(nodeName + ":" + nodePort, 5, se);
                    logger.error("", se);
                } catch (IOException ie) {

                    //ie.printStackTrace();
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                    super.setDeadNode(nodeName + ":" + nodePort, 6, ie);
                    logger.error("", ie);
                } catch (Exception ee) {

                    //ee.printStackTrace();
                    super.setDeadNode(nodeName + ":" + nodePort, 7, ee);
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                    logger.error("", ee);
                }

            }


            // スレーブノード処理
            if (subKeyNodeName != null) {

                if (slaveKeyNodeConnector == null) {
                    // SubDataNodeに送信
                    slaveKeyNodeConnector = this.createKeyNodeConnection(subKeyNodeName, subKeyNodePort, subKeyNodeFullName, false);
                }

                if (slaveKeyNodeConnector != null) {
                    // 接続結果と、現在の保存先状況で処理を分岐
                    try {

                        // 処理種別判別
                        // Subが指定されてる
                        // 遅延設定がないもしくは、mainが失敗している場合
                        if (!delayFlg || mainNodeSave == false) {

                            if (!slaveSendEnd) {
                                // 未送信の場合は送信
                                slaveKeyNodeConnector.println(sendStr);
                                slaveKeyNodeConnector.flush();
                            }

                            if (type.equals("1")) {

                                // 返却値取得
                                retParam = slaveKeyNodeConnector.readLine(sendStr);

                                // Key値でValueを保存
                                // splitは遅いので特定文字列で返却値が始まるかをチェックし始まる場合は登録成功
                                //retParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                                if (retParam != null && retParam.indexOf(ImdstDefine.keyNodeKeyRegistSuccessStr) == 0) {
                                    subNodeSave = true;
                                } else {
                                    // 論理的に登録失敗
                                    super.setDeadNode(subKeyNodeName + ":" + subKeyNodePort, 3, null);
                                    logger.error("setKeyNodeValue Logical Error Node =["  + subKeyNodeName + ":" + subKeyNodePort + "] retParam=[" + retParam + "]" + " Connectoer=[" + slaveKeyNodeConnector.connectorDump() + "]");
                                }
                            } else if (type.equals("3")) {

                                // 返却値取得
                                retParam = slaveKeyNodeConnector.readLine(sendStr);

                                // Tag値でキー値を保存

                                // splitは遅いので特定文字列で返却値が始まるかをチェックし始まる場合は登録成功
                                //retParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                                if (retParam != null && retParam.indexOf(ImdstDefine.keyNodeTagRegistSuccessStr) == 0) {
                                    subNodeSave = true;
                                } else {
                                    // 論理的に登録失敗
                                    super.setDeadNode(subKeyNodeName + ":" + subKeyNodePort, 4, null);
                                    logger.error("setKeyNodeValue Logical Error Node =["  + subKeyNodeName + ":" + subKeyNodePort + "] retParam=[" + retParam + "]" + " Connectoer=[" + slaveKeyNodeConnector.connectorDump() + "]");
                                }
                            }
                        }else {

                            // 遅延
                            // 送信
                            // 遅延の場合は結果が送られてこないのでNetworkから読みださない
                            slaveKeyNodeConnector.print("-");
                            slaveKeyNodeConnector.println(sendStr);
                            slaveKeyNodeConnector.flush();

                            subNodeSave = true;
                            // 結果を作りだす
                            if (type.equals("1")) {
                                retParam = "1,true,OK";
                            } else if (type.equals("3")) {
                                retParam = "3,true,OK";
                            }
                        }
                        // 使用済みの接続を戻す
                        super.addKeyNodeCacheConnectionPool(slaveKeyNodeConnector);
                    } catch (SocketException se) {
                        //se.printStackTrace();
                        if (keyNodeConnector != null) {
                            keyNodeConnector.close();
                            keyNodeConnector = null;
                        }
                        super.setDeadNode(subKeyNodeName + ":" + subKeyNodePort, 5, se);
                        logger.debug(se);
                    } catch (IOException ie) {
                        //ie.printStackTrace();
                        if (keyNodeConnector != null) {
                            keyNodeConnector.close();
                            keyNodeConnector = null;
                        }
                        super.setDeadNode(subKeyNodeName + ":" + subKeyNodePort, 6, ie);
                        logger.debug(ie);
                    } catch (Exception ee) {
                        //ee.printStackTrace();
                        if (keyNodeConnector != null) {
                            keyNodeConnector.close();
                            keyNodeConnector = null;
                        }
                        super.setDeadNode(subKeyNodeName + ":" + subKeyNodePort, 7, ee);
                        logger.debug(ee);
                    }
                }
            }

            // ノードへの保存状況を確認
            if (mainNodeSave == false && subNodeSave == false) {
                if (retParam == null) {
                    throw new BatchException("Key Node IO Error: detail info for log file");
                }
            }

        } catch (BatchException be) {
            //be.printStackTrace();
            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            throw be;
        } catch (Exception e) {
            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            throw new BatchException(e);
        } finally {
            // ノードの使用終了をマーク
            super.execNodeUseEnd(keyNodeFullName);

            if (subKeyNodeName != null) 
                super.execNodeUseEnd(subKeyNodeFullName);

            // 返却地値をパースする
            if (retParam != null) {
                retParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
            }
        }

        return retParams;
    }


    /**
     * KeyNodeに対してデータを保存する.<br>
     * 既に登録されている場合は失敗する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(1=Keyとデータノード設定, 3=Tagにキーを追加, 30=ロックを取得, 31=ロックを解除)
     * @param values 送信データ
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] setKeyNodeValueOnlyOnce(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String thirdKeyNodeName, String thirdKeyNodePort, String thirdKeyNodeFullName, String type, String[] values, String transactionCode) throws BatchException {
        boolean exceptionFlg = false;
        String[] ret = null;
        String[] thirdRet = null;
        BatchException retBe = null;

        try {

            ret = this.setKeyNodeValueOnlyOnce(keyNodeName, keyNodePort, keyNodeFullName, subKeyNodeName, subKeyNodePort, subKeyNodeFullName, type, values, transactionCode);
        } catch (BatchException be) {

            retBe = be;
            exceptionFlg = true;
        } catch (Exception e) {

            retBe = new BatchException(e);
            exceptionFlg = true;
        } finally {
            
            try {
                if (exceptionFlg) {
                    thirdRet = this.setKeyNodeValueOnlyOnce(thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, null, null, null, type, values, transactionCode);
                    ret = thirdRet;
                } else {
                    if (ret[1].equals("true")) {

                        // 保存成功
                        // まだ登録されていない
                        // 無条件で登録
                        thirdRet = this.setKeyNodeValue(thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, null, null, null, "1", values, transactionCode);
                    } else {
                        // サードノードの使用終了のみマーク
                        if (thirdKeyNodeFullName != null) 
                            super.execNodeUseEnd(thirdKeyNodeFullName);
                    }
                }
            } catch (Exception e) {
                if (exceptionFlg) throw retBe;
            }
        }

        return ret;
    }

    /**
     * KeyNodeに対してデータを保存する.<br>
     * 既に登録されている場合は失敗する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(1=Keyとデータノード設定, 3=Tagにキーを追加, 30=ロックを取得, 31=ロックを解除)
     * @param values 送信データ
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] setKeyNodeValueOnlyOnce(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String type, String[] values, String transactionCode) throws BatchException {

        KeyNodeConnector keyNodeConnector = null;

        String nodeName = keyNodeName;
        String nodePort = keyNodePort;
        String nodeFullName = keyNodeFullName;

        String[] retParams = null;
        String[] mainRetParams = null;
        String[] subRetParams = null;

        int counter = 0;

        String tmpSaveHost = null;
        String[] tmpSaveData = null;
        String retParam = null;

        boolean mainNodeSave = false;
        boolean mainNodeNetworkError = false;
        boolean subNodeSave = false;
        boolean subNodeNetworkError = false;

        try {

            // TransactionModeの状態に合わせてLock状態を確かめる
            if (transactionMode) {
                while (true) {

                    // TransactionMode時
                    // TransactionManagerに処理を依頼
                    String[] keyNodeLockRet = hasLockKeyNode(transactionManagerInfo[0], transactionManagerInfo[1], values[0]);

                    // 取得結果確認
                    if (keyNodeLockRet[1].equals("true")) {

                        if (keyNodeLockRet[2].equals(transactionCode)) break;
                    } else {
                        break;
                    }
                }
            }


            // 旧ルールが存在する場合はまず確認
            // TODO:このやり方ではノード追加後の移行中に完全性が崩れる可能性がある
            if (DataDispatcher.hasOldRule()) {
                String decodeIsokationCnvKey = this.decodeIsolationConvert(values[0]);
                String[] checkRet = this.getKeyValue(decodeIsokationCnvKey);
                if (checkRet[0].equals("true")) {
                    // 旧ノードにデータあり
                    retParams = new String[3];
                    retParams[0] = "6";
                    retParams[1] = "false";
                    retParams[2] = ImdstDefine.keyNodeKeyNewRegistErrMsg;
                    return retParams;
                }
            }
            

            // まずメインデータノードへデータ登録
            // KeyNodeとの接続を確立
            keyNodeConnector = this.createKeyNodeConnection(keyNodeName, keyNodePort, keyNodeFullName, false);

            if (keyNodeConnector != null) {
                try {

                    // Key値でデータノード名を保存
                    StringBuilder buf = new StringBuilder(ImdstDefine.stringBufferMiddleSize);
                    String sendStr = null;
                    // パラメータ作成 キー値のハッシュ値文字列[セパレータ]データノード名
                    buf.append("6");                                     // Type
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(this.stringCnv(values[0]));               // Key値
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(transactionCode);                         // Transaction値
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(values[1]);                               // Value値
                    buf.append(ImdstDefine.setTimeParamSep);
                    buf.append(setTime);                                 // 保存バージョン
                    sendStr = buf.toString();

                    // 送信
                    keyNodeConnector.println(sendStr);
                    keyNodeConnector.flush();

                    // 返却値取得
                    retParam = keyNodeConnector.readLine(sendStr);

                    // 使用済みの接続を戻す
                    super.addKeyNodeCacheConnectionPool(keyNodeConnector);

                    // splitは遅いので特定文字列で返却値が始まるかをチェックし始まる場合は登録成功
                    //retParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                    if (retParam.indexOf(ImdstDefine.keyNodeKeyNewRegistSuccessStr) == 0) {

                        mainNodeSave = true;
                        mainRetParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                    } else {

                        mainNodeSave = false;
                        mainRetParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                    }
                } catch (SocketException se) {
                    mainNodeNetworkError = true;
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                    super.setDeadNode(keyNodeName + ":" + keyNodePort, 8, se);
                    logger.debug(se);
                } catch (IOException ie) {
                    mainNodeNetworkError = true;
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                    super.setDeadNode(keyNodeName + ":" + keyNodePort, 9, ie);
                    logger.debug(ie);
                } catch (Exception ee) {
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                    super.setDeadNode(keyNodeName + ":" + keyNodePort, 10, ee);
                    logger.debug(ee);
                }

            } else {
                mainNodeNetworkError = true;
            }


            if (subKeyNodeName != null) {
                // Subノードで実施
                if (mainNodeSave == true || (mainNodeSave == false && mainNodeNetworkError == true)) {
                    String subNodeExecType = "";

                    // Mainノードが処理成功もしくは、ネットワークエラーの場合はSubノードの処理を行う。
                    // KeyNodeとの接続を確立
                    if (!mainNodeSave) {
                        subNodeExecType = "6";
                    } else {
                        subNodeExecType = "1";
                    }

                    keyNodeConnector = this.createKeyNodeConnection(subKeyNodeName, subKeyNodePort, subKeyNodeFullName, false);

                    if (keyNodeConnector != null) {
                        try {

                            // Key値でデータノード名を保存
                            StringBuilder buf = new StringBuilder(ImdstDefine.stringBufferMiddleSize);
                            String sendStr = null;

                            // パラメータ作成 キー値のハッシュ値文字列[セパレータ]データノード名
                            buf.append(subNodeExecType);                         // Type
                            buf.append(ImdstDefine.keyHelperClientParamSep);
                            buf.append(this.stringCnv(values[0]));               // Key値
                            buf.append(ImdstDefine.keyHelperClientParamSep);
                            buf.append(transactionCode);                         // Transaction値
                            buf.append(ImdstDefine.keyHelperClientParamSep);
                            buf.append(values[1]);                               // Value値
                            buf.append(ImdstDefine.setTimeParamSep);
                            buf.append(setTime);                                 // 保存バージョン
                            sendStr = buf.toString();

                            // 送信
                            keyNodeConnector.println(sendStr);
                            keyNodeConnector.flush();

                            // 返却値取得
                            retParam = keyNodeConnector.readLine(sendStr);

                            // 使用済みの接続を戻す
                            super.addKeyNodeCacheConnectionPool(keyNodeConnector);


                            // splitは遅いので特定文字列で返却値が始まるかをチェックし始まる場合は登録成功
                            //retParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                            if (retParam.indexOf(ImdstDefine.keyNodeKeyNewRegistSuccessStr) == 0) {

                                subNodeSave = true;
                                subRetParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                            } else {

                                subNodeSave = false;
                                subRetParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                            }
                        } catch (SocketException se) {
                            subNodeNetworkError = true;
                            if (keyNodeConnector != null) {
                                keyNodeConnector.close();
                                keyNodeConnector = null;
                            }
                            super.setDeadNode(subKeyNodeName + ":" + subKeyNodePort, 11, se);
                            logger.debug(se);
                        } catch (IOException ie) {
                            subNodeNetworkError = true;
                            if (keyNodeConnector != null) {
                                keyNodeConnector.close();
                                keyNodeConnector = null;
                            }
                            super.setDeadNode(subKeyNodeName + ":" + subKeyNodePort, 12, ie);
                            logger.debug(ie);
                        } catch (Exception ee) {
                            if (keyNodeConnector != null) {
                                keyNodeConnector.close();
                                keyNodeConnector = null;
                            }
                            super.setDeadNode(subKeyNodeName + ":" + subKeyNodePort, 13, ee);
                            logger.debug(ee);
                        }

                    } else {
                        subNodeNetworkError = true;
                    }
                }
            }

            // Main、Sub両方ともネットワークでのエラーがであるか確認
            if (mainNodeNetworkError == true && subNodeNetworkError == true) {
                // ネットワークエラー
                throw new BatchException("Key Node IO Error: detail info for log file");
            }

            // ノードへの保存状況を確認
            if (mainNodeSave == false) {

                // MainNode保存失敗
                if (mainNodeNetworkError == false) {

                    // 既に書き込み済みでの失敗 
                    retParams = mainRetParams;
                }
            } else {

                // MainNode保存成功
                retParams = mainRetParams;
            }

    
            if (subKeyNodeName != null) {
                // スレーブノードが存在する場合のみ
                // MainNodeが既にデータ有りで失敗せずに、成功もしていない
                if (retParams == null) {

                    if (subNodeSave == false) {

                        // SubNode保存失敗
                        if (subNodeNetworkError == false) {

                            // 既に書き込み済みでの失敗 
                            retParams = subRetParams;
                        }
                    } else {

                        // SubNode保存成功
                        retParams = subRetParams;
                    }
                }
            }
        } catch (BatchException be) {
            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            throw be;
        } catch (Exception e) {
            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            throw new BatchException(e);
        } finally {
            // ノードの使用終了をマーク
            super.execNodeUseEnd(keyNodeFullName);

            if (subKeyNodeName != null) 
                super.execNodeUseEnd(subKeyNodeFullName);
        }

        return retParams;
    }



    /**
     * KeyNodeに対してデータを保存する.<br>
     * 同時に排他制御を行う(cas相当).<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(1=Keyとデータノード設定, 3=Tagにキーを追加, 30=ロックを取得, 31=ロックを解除)
     * @param values 送信データ
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] setKeyNodeValueVersionCheck(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String thirdKeyNodeName, String thirdKeyNodePort, String thirdKeyNodeFullName, String type, String[] values, String transactionCode, String checkVersionNo) throws BatchException {
        boolean exceptionFlg = false;
        String[] ret = null;
        String[] thirdRet = null;
        BatchException retBe = null;

        try {

            ret = this.setKeyNodeValueVersionCheck(keyNodeName, keyNodePort, keyNodeFullName, subKeyNodeName, subKeyNodePort, subKeyNodeFullName, type, values, transactionCode, checkVersionNo, true);
        } catch (BatchException be) {

            retBe = be;
            exceptionFlg = true;
        } catch (Exception e) {

            retBe = new BatchException(e);
            exceptionFlg = true;
        } finally {
            
            try {
                if (exceptionFlg) {
                    thirdRet = this.setKeyNodeValueVersionCheck(thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, null, null, null, type, values, transactionCode, checkVersionNo, true);
                    ret = thirdRet;
                } else {
                    if (ret[1].equals("true")) {

                        // 無条件で登録
                        // ここでversionチェックせずに登録するほうのメソッドを呼び出す
                        thirdRet = this.setKeyNodeValueVersionCheck(thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, null, null, null, "1", values, transactionCode, checkVersionNo, false);
                    } else {
                        // サードノードの使用終了のみマーク
                        if (thirdKeyNodeFullName != null) 
                            super.execNodeUseEnd(thirdKeyNodeFullName);
                    }
                }
            } catch (Exception e) {
                if (exceptionFlg) throw retBe;
            }
        }

        return ret;
    }

    /**
     * KeyNodeに対してデータを保存する.<br>
     * 同時に排他制御を行う(cas相当).<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(1=Keyとデータノード設定, 3=Tagにキーを追加, 30=ロックを取得, 31=ロックを解除)
     * @param values 送信データ
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] setKeyNodeValueVersionCheck(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String type, String[] values, String transactionCode, String checkVersionNo, boolean execCheck) throws BatchException {

        KeyNodeConnector keyNodeConnector = null;

        String nodeName = keyNodeName;
        String nodePort = keyNodePort;
        String nodeFullName = keyNodeFullName;

        String[] retParams = null;
        String[] mainRetParams = null;
        String[] subRetParams = null;

        int counter = 0;

        String tmpSaveHost = null;
        String[] tmpSaveData = null;
        String retParam = null;

        boolean mainNodeSave = false;
        boolean mainNodeNetworkError = false;
        boolean subNodeSave = false;
        boolean subNodeNetworkError = false;

        try {

            // TransactionModeの状態に合わせてLock状態を確かめる
            if (transactionMode) {
                while (true) {

                    // TransactionMode時
                    // TransactionManagerに処理を依頼
                    String[] keyNodeLockRet = hasLockKeyNode(transactionManagerInfo[0], transactionManagerInfo[1], values[0]);

                    // 取得結果確認
                    if (keyNodeLockRet[1].equals("true")) {

                        if (keyNodeLockRet[2].equals(transactionCode)) break;
                    } else {
                        break;
                    }
                }
            }


            // まずメインデータノードへデータ登録
            // KeyNodeとの接続を確立
            keyNodeConnector = this.createKeyNodeConnection(keyNodeName, keyNodePort, keyNodeFullName, false);

            if (keyNodeConnector != null) {
                try {

                    // Key値でデータノード名を保存
                    StringBuilder buf = new StringBuilder(ImdstDefine.stringBufferMiddleSize);
                    String sendStr = null;

                    // パラメータ作成 キー値のハッシュ値文字列[セパレータ]データノード名
                    buf.append("16");                                    // Type
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(this.stringCnv(values[0]));               // Key値
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(transactionCode);                         // Transaction値
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(checkVersionNo);                          // 保存バージョン
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(execCheck);                               // チェック有無
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(values[1]);                               // Value値
                    sendStr = buf.toString();

                    // 送信
                    keyNodeConnector.println(sendStr);
                    keyNodeConnector.flush();

                    // 返却値取得
                    retParam = keyNodeConnector.readLine(sendStr);

                    // 使用済みの接続を戻す
                    super.addKeyNodeCacheConnectionPool(keyNodeConnector);

                    // splitは遅いので特定文字列で返却値が始まるかをチェックし始まる場合は登録成功
                    if (retParam.indexOf(ImdstDefine.keyNodeUpdateVersionCheckSuccessStr) == 0) {

                        mainNodeSave = true;
                        mainRetParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                    } else {

                        mainNodeSave = false;
                        mainRetParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                    }
                } catch (SocketException se) {
                    mainNodeNetworkError = true;
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                    super.setDeadNode(keyNodeName + ":" + keyNodePort, 115, se);
                    logger.debug(se);
                } catch (IOException ie) {
                    mainNodeNetworkError = true;
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                    super.setDeadNode(keyNodeName + ":" + keyNodePort, 116, ie);
                    logger.debug(ie);
                } catch (Exception ee) {
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                    super.setDeadNode(keyNodeName + ":" + keyNodePort, 117, ee);
                    logger.debug(ee);
                }

            } else {
                mainNodeNetworkError = true;
            }


            if (subKeyNodeName != null ) {
                // Subノードで実施
                if (mainNodeSave == true || (mainNodeSave == false && mainNodeNetworkError == true)) {

                    // Mainノードが処理成功もしくは、ネットワークエラーの場合はSubノードの処理を行う。
                    if (mainNodeSave) {
                        // Mainノードで登録が成功している
                        // 無条件(バージョンチェックなし)で登録
                        execCheck = false;
                    } else {
                        // Mainノードがネットワーク障害で失敗している
                        // バージョンチェックを行う
                        execCheck = true;
                    }

                    keyNodeConnector = this.createKeyNodeConnection(subKeyNodeName, subKeyNodePort, subKeyNodeFullName, false);

                    if (keyNodeConnector != null) {
                        try {

                            // Key値でデータノード名を保存
                            StringBuilder buf = new StringBuilder(ImdstDefine.stringBufferMiddleSize);
                            String sendStr = null;

                            // パラメータ作成 キー値のハッシュ値文字列[セパレータ]データノード名
                            buf.append("16");                                    // Type
                            buf.append(ImdstDefine.keyHelperClientParamSep);
                            buf.append(this.stringCnv(values[0]));               // Key値
                            buf.append(ImdstDefine.keyHelperClientParamSep);
                            buf.append(transactionCode);                         // Transaction値
                            buf.append(ImdstDefine.keyHelperClientParamSep);
                            buf.append(checkVersionNo);                          // 保存バージョン
                            buf.append(ImdstDefine.keyHelperClientParamSep);
                            buf.append(execCheck);                               // チェック有無
                            buf.append(ImdstDefine.keyHelperClientParamSep);
                            buf.append(values[1]);                               // Value値

                            sendStr = buf.toString();

                            // 送信
                            keyNodeConnector.println(sendStr);
                            keyNodeConnector.flush();

                            // 返却値取得
                            retParam = keyNodeConnector.readLine(sendStr);

                            // 使用済みの接続を戻す
                            super.addKeyNodeCacheConnectionPool(keyNodeConnector);


                            // splitは遅いので特定文字列で返却値が始まるかをチェックし始まる場合は登録成功
                            //retParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                            if (retParam.indexOf(ImdstDefine.keyNodeUpdateVersionCheckSuccessStr) == 0) {

                                subNodeSave = true;
                                subRetParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                            } else {

                                subNodeSave = false;
                                subRetParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                            }
                        } catch (SocketException se) {
                            subNodeNetworkError = true;
                            if (keyNodeConnector != null) {
                                keyNodeConnector.close();
                                keyNodeConnector = null;
                            }
                            super.setDeadNode(subKeyNodeName + ":" + subKeyNodePort, 125, se);
                            logger.debug(se);
                        } catch (IOException ie) {
                            subNodeNetworkError = true;
                            if (keyNodeConnector != null) {
                                keyNodeConnector.close();
                                keyNodeConnector = null;
                            }
                            super.setDeadNode(subKeyNodeName + ":" + subKeyNodePort, 126, ie);
                            logger.debug(ie);
                        } catch (Exception ee) {
                            if (keyNodeConnector != null) {
                                keyNodeConnector.close();
                                keyNodeConnector = null;
                            }
                            super.setDeadNode(subKeyNodeName + ":" + subKeyNodePort, 127, ee);
                            logger.debug(ee);
                        }

                    } else {
                        subNodeNetworkError = true;
                    }
                }
            }

            // Main、Sub両方ともネットワークでのエラーであるか確認
            if (mainNodeNetworkError == true && subNodeNetworkError == true) {
                // ネットワークエラー
                throw new BatchException("Key Node IO Error: detail info for log file");
            }

            // ノードへの保存状況を確認
            if (mainNodeSave == false) {

                // MainNode保存失敗
                if (mainNodeNetworkError == false) {

                    // 既に書き込み済みでの失敗 
                    retParams = mainRetParams;
                }
            } else {

                // MainNode保存成功
                retParams = mainRetParams;
            }

    
            if (subKeyNodeName != null) {
                // スレーブノードが存在する場合のみ
                // MainNodeが既にデータ有りで失敗せずに、成功もしていない
                if (retParams == null) {

                    if (subNodeSave == false) {

                        // SubNode保存失敗
                        if (subNodeNetworkError == false) {

                            // バージョン違いでの失敗
                            retParams = subRetParams;
                        }
                    } else {

                        // SubNode保存成功
                        retParams = subRetParams;
                    }
                }
            }
        } catch (BatchException be) {
            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            throw be;
        } catch (Exception e) {
            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            throw new BatchException(e);
        } finally {
            // ノードの使用終了をマーク
            super.execNodeUseEnd(keyNodeFullName);

            if (subKeyNodeName != null) 
                super.execNodeUseEnd(subKeyNodeFullName);
        }

        return retParams;
    }




    /**
     * KeyNodeからデータを更新し、取得する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(8=Keyでデータを取得)
     * @param scriptStr Script文字列
     * @param key Key値
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] matchTargetKeyPairValueCharacter(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String thirdKeyNodeName, String thirdKeyNodePort, String thirdKeyNodeFullName, String keys, String characters, String type, KeyNodeConnector keyNodeConnector) throws BatchException {
        boolean exceptionFlg = false;
        String[] ret = null;
        String[] thirdRet = null;
        BatchException retBe = null;

        try {

            ret = this.matchTargetKeyPairValueCharacter(keyNodeName, keyNodePort, keyNodeFullName, subKeyNodeName, subKeyNodePort, subKeyNodeFullName, keys, characters, type, keyNodeConnector);
        } catch (BatchException be) {

            retBe = be;
            exceptionFlg = true;
        } catch (Exception e) {

            retBe = new BatchException(e);
            exceptionFlg = true;
        } finally {
            
            try {
                if (exceptionFlg) {
                    thirdRet = this.matchTargetKeyPairValueCharacter(thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, null, null, null, keys, characters, type, keyNodeConnector);
                    ret = thirdRet;
                } else {
                    // サードノードの使用終了のみマーク
                    if (thirdKeyNodeFullName != null) 
                        super.execNodeUseEnd(thirdKeyNodeFullName);
                }
            } catch (Exception e) {
                if (exceptionFlg) throw retBe;
            }
        }

        return ret;
    }


    /**
     * KeyNodeからデータを取得する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param type 処理タイプ(8=Keyでデータを取得)
     * @param scriptStr Script文字列
     * @param key Key値
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] matchTargetKeyPairValueCharacter(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String keys, String characters, String type, KeyNodeConnector keyNodeConnector) throws BatchException {

        String[] retParams = null;
        String[] cnvConsistencyRet = null;

        boolean slaveUse = false;
        boolean mainRetry = false;

        String nowUseNodeInfo = null;

        String sendStr = null;
        StringBuilder buf = new StringBuilder(ImdstDefine.stringBufferMiddleSize);

        // パラメータ作成 処理タイプ[セパレータ]キー値のハッシュ値文字列
        buf.append("50");
        buf.append(ImdstDefine.keyHelperClientParamSep);
        buf.append(this.stringCnv(keys));
        buf.append(ImdstDefine.keyHelperClientParamSep);
        buf.append(characters);
        buf.append(ImdstDefine.keyHelperClientParamSep);
        buf.append(type);

        sendStr = buf.toString();

        SocketException se = null;
        IOException ie = null;

        try {

            while (true) {

                // 戻り値がnullの場合は何だかの理由で接続に失敗しているのでスレーブの設定がある場合は接続する
                // スレーブの設定がない場合は、エラーとしてExceptionをthrowする
                if (keyNodeConnector == null) {

                    // KeyNodeとの接続を確立
                    if (subKeyNodeName != null) {
                        keyNodeConnector = this.createKeyNodeConnection(subKeyNodeName, subKeyNodePort, subKeyNodeFullName, false);
                        if (keyNodeConnector == null) throw new BatchException("Key Node IO Error: detail info for log file");

                        // 送信
                        keyNodeConnector.println(sendStr);
                        keyNodeConnector.flush();
                    }

                    slaveUse = true;
                }

                try {

                    // 返却値取得
                    String retParam = keyNodeConnector.readLine(sendStr);

                    // 返却値を分解
                    // 処理番号, true or false, valueの想定
                    retParams = retParam.split(ImdstDefine.keyHelperClientParamSep);

                    // 使用済みの接続を戻す
                    super.addKeyNodeCacheConnectionPool(keyNodeConnector);

                    if (retParams != null && retParams.length > 1 && !retParams[1].equals("error")) break;
                } catch(SocketException tSe) {
                    // ここでのエラーは通信中に発生しているので、スレーブノードを使用していない場合のみ再度スレーブへの接続を試みる
                    se = tSe;
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                } catch(IOException tIe) {
                    // ここでのエラーは通信中に発生しているので、スレーブノードを使用していない場合のみ再度スレーブへの接続を試みる
                    ie = tIe;
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                }

                // 既にスレーブの接続を使用している場合は、もう一度だけメインノードに接続を試みる
                // それで駄目な場合はエラーとする
                if (slaveUse) {
                    if (mainRetry) {
                        if (se != null) throw se;
                        if (ie != null) throw ie;
                        throw new BatchException("Key Node IO Error: detail info for log file");
                    } else {

                        // メインKeyNodeとの接続を確立
                        keyNodeConnector = this.createKeyNodeConnection(keyNodeName, keyNodePort, keyNodeFullName, true);
                        if (keyNodeConnector == null) throw new BatchException("Key Node IO Error: detail info for log file");
                        
                        // 送信
                        keyNodeConnector.println(sendStr);
                        keyNodeConnector.flush();

                        mainRetry = true;
                    }
                } else {
                    if (subKeyNodeName == null) {
                        if (se != null) throw se;
                        if (ie != null) throw ie;
                    } else{
                        keyNodeConnector = null;
                    }
                }
            }
        } catch (Exception e) {
            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            throw new BatchException(e);
        } finally {
            // ノードの使用終了をマーク
            super.execNodeUseEnd(keyNodeFullName);

            if (subKeyNodeName != null) 
                super.execNodeUseEnd(subKeyNodeFullName);
        }
        return retParams;
    }


    /**
     * KeyNodeに対してデータを削除する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param keyStr Keyデータ
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] removeKeyNodeValue(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String thirdKeyNodeName, String thirdKeyNodePort, String thirdKeyNodeFullName, String key, String transactionCode) throws BatchException {

        boolean exceptionFlg = false;
        String[] ret = null;
        String[] thirdRet = null;
        BatchException retBe = null;

        try {

            ret = this.removeKeyNodeValue(keyNodeName, keyNodePort, keyNodeFullName, subKeyNodeName, subKeyNodePort, subKeyNodeFullName, key, transactionCode);
            if (ret == null) throw new BatchException("removeKeyNodeValue - RetParam = null");
        } catch (BatchException be) {

            retBe = be;
            exceptionFlg = true;
        } catch (Exception e) {

            retBe = new BatchException(e);
            exceptionFlg = true;
        } finally {
            
            try {
                thirdRet = this.removeKeyNodeValue(thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, null, null, null, key, transactionCode);
                if (exceptionFlg) ret = thirdRet;
            } catch (Exception e) {
                if (exceptionFlg) throw retBe;
            }
        }

        return ret;
    }


    /**
     * KeyNodeに対してデータを削除する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param keyStr Keyデータ
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] removeKeyNodeValue(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String key, String transactionCode) throws BatchException {

        KeyNodeConnector keyNodeConnector = null;

        String nodeName = keyNodeName;
        String nodePort = keyNodePort;
        String nodeFullName = keyNodeFullName;

        String[] retParams = null;
        String[] cnvConsistencyRet = null;

        int counter = 0;

        String tmpSaveHost = null;
        String[] tmpSaveData = null;
        String retParam = null;

        boolean mainNodeSave = false;
        boolean subNodeSave = false;
        try {
            // TransactionModeの状態に合わせてLock状態を確かめる
            if (transactionMode) {
                while (true) {
                    // TransactionMode時

                    // TransactionManagerに処理を依頼
                    String[] keyNodeLockRet = hasLockKeyNode(transactionManagerInfo[0], transactionManagerInfo[1], key);

                    // 取得結果確認
                    if (keyNodeLockRet[1].equals("true")) {
                        if (keyNodeLockRet[2].equals(transactionCode)) break;
                    } else {
                        break;
                    }
                }
            }

            do {
                // KeyNodeとの接続を確立
                keyNodeConnector = this.createKeyNodeConnection(nodeName, nodePort, nodeFullName, false);


                // 接続結果と、現在の保存先状況で処理を分岐
                if (keyNodeConnector != null) {
                    try {

                        // Key値でデータノード名を保存
                        StringBuilder buf = new StringBuilder(ImdstDefine.stringBufferSmallSize);
                        String sendStr = null;
                        // パラメータ作成 キー値のハッシュ値文字列[セパレータ]データノード名
                        buf.append("5");
                        buf.append(ImdstDefine.keyHelperClientParamSep);
                        buf.append(this.stringCnv(key));
                        buf.append(ImdstDefine.keyHelperClientParamSep);
                        buf.append(transactionCode);
                        sendStr = buf.toString();

                        // 送信
                        keyNodeConnector.println(sendStr);
                        keyNodeConnector.flush();

                        // 返却値取得
                        retParam = keyNodeConnector.readLine(sendStr);

                        // 使用済みの接続を戻す
                        super.addKeyNodeCacheConnectionPool(keyNodeConnector);

                        // splitは遅いので特定文字列で返却値が始まるかをチェックし始まる場合は登録成功
                        if (retParam != null && retParam.indexOf(ImdstDefine.keyNodeKeyRemoveSuccessStr) == 0) {
                            if (counter == 0) mainNodeSave = true;
                            if (counter == 1) subNodeSave = true;
                        } else if (retParam == null || retParam.indexOf(ImdstDefine.keyNodeKeyRemoveNotFoundStr) != 0){
                            // 論理的に削除失敗
                            super.setDeadNode(nodeName + ":" + nodePort, 14, null);
                            logger.error("removeKeyNodeValue Logical Error Node =["  + nodeName + ":" + nodePort + "] retParam=[" + retParam + "]" + " Connectoer=[" + keyNodeConnector.connectorDump() + "]");

                        }
                    } catch (SocketException se) {

                        if (keyNodeConnector != null) {
                            keyNodeConnector.close();
                            keyNodeConnector = null;
                        }
                        super.setDeadNode(nodeName + ":" + nodePort, 15, se);
                        logger.debug(se);
                    } catch (IOException ie) {

                        if (keyNodeConnector != null) {
                            keyNodeConnector.close();
                            keyNodeConnector = null;
                        }
                        super.setDeadNode(nodeName + ":" + nodePort, 16, ie);
                        logger.debug(ie);
                    } catch (Exception ee) {

                        if (keyNodeConnector != null) {
                            keyNodeConnector.close();
                            keyNodeConnector = null;
                        }
                        super.setDeadNode(nodeName + ":" + nodePort, 17, ee);
                        logger.debug(ee);
                    }
                }

                // スレーブデータノードの名前を代入
                nodeName = subKeyNodeName;
                nodePort = subKeyNodePort;
                nodeFullName = subKeyNodeFullName;

                counter++;
                // スレーブデータノードが存在しない場合もしくは、既に2回保存を実施した場合は終了
            } while(nodeName != null && counter < 2);

        } catch (BatchException be) {

            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            throw be;
        } catch (Exception e) {

            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            throw new BatchException(e);
        } finally {
            // ノードの使用終了をマーク
            super.execNodeUseEnd(keyNodeFullName);

            if (subKeyNodeName != null) 
                super.execNodeUseEnd(subKeyNodeFullName);

            // 返却地値をパースする
            if (retParam != null) {

                retParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                if (retParams[1].equals("true")) {
                    cnvConsistencyRet = dataConvert4Consistency(retParams[2]);
                    retParams[2] = cnvConsistencyRet[0];
                }
            }
        }

        return retParams;
    }


    /**
     * KeyNodeに対してKeyに紐付くTagの削除を依頼する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param tag Tagデータ
     * @param key Keyデータ
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] removeTargetTagInKey(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String thirdKeyNodeName, String thirdKeyNodePort, String thirdKeyNodeFullName, String tag, String key, String transactionCode) throws BatchException {

        boolean exceptionFlg = false;
        String[] ret = null;
        String[] thirdRet = null;
        BatchException retBe = null;

        try {

            ret = this.removeTargetTagInKey(keyNodeName, keyNodePort, keyNodeFullName, subKeyNodeName, subKeyNodePort, subKeyNodeFullName, tag, key, transactionCode);
            if (ret == null) throw new BatchException("removeTargetTagInKey - RetParam = null");
        } catch (BatchException be) {

            retBe = be;
            exceptionFlg = true;
        } catch (Exception e) {

            retBe = new BatchException(e);
            exceptionFlg = true;
        } finally {
            
            try {
                thirdRet = this.removeTargetTagInKey(thirdKeyNodeName, thirdKeyNodePort, thirdKeyNodeFullName, null, null, null, tag, key, transactionCode);
                if (exceptionFlg) ret = thirdRet;
            } catch (Exception e) {
                if (exceptionFlg) throw retBe;
            }
        }

        return ret;
    }


    /**
     * KeyNodeに対してデータを削除する.<br>
     * 
     * @param keyNodeName マスターデータノードの名前(IPなど)
     * @param keyNodePort マスターデータノードのアクセスポート番号
     * @param subKeyNodeName スレーブデータノードの名前(IPなど)
     * @param subKeyNodePort スレーブデータノードのアクセスポート番号
     * @param tag Tagデータ
     * @param key Keyデータ
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] removeTargetTagInKey(String keyNodeName, String keyNodePort, String keyNodeFullName, String subKeyNodeName, String subKeyNodePort, String subKeyNodeFullName, String tag, String key, String transactionCode) throws BatchException {

        KeyNodeConnector keyNodeConnector = null;

        String nodeName = keyNodeName;
        String nodePort = keyNodePort;
        String nodeFullName = keyNodeFullName;

        String[] retParams = null;
        String[] cnvConsistencyRet = null;

        int counter = 0;

        String tmpSaveHost = null;
        String[] tmpSaveData = null;
        String retParam = null;

        boolean mainNodeSave = false;
        boolean subNodeSave = false;
        try {
            // TransactionModeの状態に合わせてLock状態を確かめる
            if (transactionMode) {
                while (true) {
                    // TransactionMode時

                    // TransactionManagerに処理を依頼
                    String[] keyNodeLockRet = hasLockKeyNode(transactionManagerInfo[0], transactionManagerInfo[1], tag);

                    // 取得結果確認
                    if (keyNodeLockRet[1].equals("true")) {
                        if (keyNodeLockRet[2].equals(transactionCode)) break;
                    } else {
                        break;
                    }
                }
            }

            do {
                // KeyNodeとの接続を確立
                keyNodeConnector = this.createKeyNodeConnection(nodeName, nodePort, nodeFullName, false);


                // 接続結果と、現在の保存先状況で処理を分岐
                if (keyNodeConnector != null) {
                    try {

                        // Key値でデータノード名を保存
                        StringBuilder buf = new StringBuilder(ImdstDefine.stringBufferSmallSize);
                        String sendStr = null;
                        // パラメータ作成 キー値のハッシュ値文字列[セパレータ]データノード名
                        buf.append("40");
                        buf.append(ImdstDefine.keyHelperClientParamSep);
                        buf.append(this.stringCnv(tag));
                        buf.append(ImdstDefine.keyHelperClientParamSep);
                        buf.append(this.stringCnv(key));
                        buf.append(ImdstDefine.keyHelperClientParamSep);
                        buf.append(transactionCode);
                        sendStr = buf.toString();

                        // 送信
                        keyNodeConnector.println(sendStr);
                        keyNodeConnector.flush();

                        // 返却値取得
                        retParam = keyNodeConnector.readLine(sendStr);

                        // 使用済みの接続を戻す
                        super.addKeyNodeCacheConnectionPool(keyNodeConnector);

                        // splitは遅いので特定文字列で返却値が始まるかをチェックし始まる場合は登録成功
                        if (retParam != null && retParam.indexOf(ImdstDefine.keyNodeTgaInKeyRemoveSuccessStr) == 0) {
                            if (counter == 0) mainNodeSave = true;
                            if (counter == 1) subNodeSave = true;
                        } else if (retParam == null || retParam.indexOf(ImdstDefine.keyNodeTgaInKeyRemoveNotFoundStr) != 0){
                            // 論理的に削除失敗
                            super.setDeadNode(nodeName + ":" + nodePort, 14, null);
                            logger.error("removeTargetTagInKey Logical Error Node =["  + nodeName + ":" + nodePort + "] retParam=[" + retParam + "]" + " Connectoer=[" + keyNodeConnector.connectorDump() + "]");

                        }
                    } catch (SocketException se) {

                        if (keyNodeConnector != null) {
                            keyNodeConnector.close();
                            keyNodeConnector = null;
                        }
                        super.setDeadNode(nodeName + ":" + nodePort, 15, se);
                        logger.debug(se);
                    } catch (IOException ie) {

                        if (keyNodeConnector != null) {
                            keyNodeConnector.close();
                            keyNodeConnector = null;
                        }
                        super.setDeadNode(nodeName + ":" + nodePort, 16, ie);
                        logger.debug(ie);
                    } catch (Exception ee) {

                        if (keyNodeConnector != null) {
                            keyNodeConnector.close();
                            keyNodeConnector = null;
                        }
                        super.setDeadNode(nodeName + ":" + nodePort, 17, ee);
                        logger.debug(ee);
                    }
                }

                // スレーブデータノードの名前を代入
                nodeName = subKeyNodeName;
                nodePort = subKeyNodePort;
                nodeFullName = subKeyNodeFullName;

                counter++;
                // スレーブデータノードが存在しない場合もしくは、既に2回保存を実施した場合は終了
            } while(nodeName != null && counter < 2);

        } catch (BatchException be) {

            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            throw be;
        } catch (Exception e) {

            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            throw new BatchException(e);
        } finally {
            // ノードの使用終了をマーク
            super.execNodeUseEnd(keyNodeFullName);

            if (subKeyNodeName != null) 
                super.execNodeUseEnd(subKeyNodeFullName);

            // 返却地値をパースする
            if (retParam != null) {

                retParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
            }
        }

        return retParams;
    }


    /**
     * KeyNodeに対してデータインクリメント、デクリメントを行う.<br>
     * 結果を返却する.<br>
     *
     * @param keyNodeName ノードフルネームの名前(IPなど)
     * @param keyNodePort ノードフルネームのアクセスポート番号
     * @param keyNodeFullName ノードフルネーム
     * @param key キー値
     * @param calcValue 演算値
     * @param transactionCode トランザクションコード
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] calcKeyValue(String keyNodeName, String keyNodePort, String keyNodeFullName, String key, String calcValue, String transactionCode) throws BatchException {
        KeyNodeConnector keyNodeConnector = null;
        KeyNodeConnector slaveKeyNodeConnector = null;

        String nodeName = keyNodeName;
        String nodePort = keyNodePort;
        String nodeFullName = keyNodeFullName;

        String[] retParams = null;

        int counter = 0;

        String tmpSaveHost = null;
        String[] tmpSaveData = null;
        String retParam = null;

        boolean mainNodeSave = false;

        StringBuilder buf = new StringBuilder(ImdstDefine.stringBufferSmallSize);
        String sendStr = null;

        try {

            // TransactionModeの状態に合わせてLock状態を確かめる
            if (transactionMode) {
                while (true) {

                    // TransactionMode時
                    // TransactionManagerに処理を依頼
                    String[] keyNodeLockRet = hasLockKeyNode(transactionManagerInfo[0], transactionManagerInfo[1], key);

                    // 取得結果確認
                    if (keyNodeLockRet[1].equals("true")) {

                        if (keyNodeLockRet[2].equals(transactionCode)) break;
                    } else {
                        break;
                    }
                }
            }

            // 送信パラメータ作成 キー値のハッシュ値文字列[セパレータ]データノード名
            buf.append("13");
            buf.append(ImdstDefine.keyHelperClientParamSep);
            buf.append(this.stringCnv(key));                     // Key値
            buf.append(ImdstDefine.keyHelperClientParamSep);
            buf.append(transactionCode);                         // Transaction値
            buf.append(ImdstDefine.keyHelperClientParamSep);
            buf.append(calcValue);                               // Value値
            sendStr = buf.toString();

            // KeyNodeとの接続を確立
            keyNodeConnector = this.createKeyNodeConnection(nodeName, nodePort, nodeFullName, false);

            // DataNodeに送信
            if (keyNodeConnector != null) {
                // 接続結果と、現在の保存先状況で処理を分岐
                try {

                    // 送信
                    // 演算処理をReadTimeoutになってもすぐに再送すると2重で計算してしますので、
                    // ReadTimeout時間を長く設定する。
                    // 特にRecover時の挙動に合わせて長く設定している。
                    keyNodeConnector.setSoTimeout(ImdstDefine.nodeConnectionTimeout4RecoverMode);
                    keyNodeConnector.println(sendStr);
                    keyNodeConnector.flush();

                    // 返却値取得
                    retParam = keyNodeConnector.readLine(sendStr);
                    keyNodeConnector.setSoTimeout(ImdstDefine.nodeConnectionTimeout);

                    // Key値でValueを保存
                    // 特定文字列で返却値が始まるかをチェックし始まる場合は登録成功
                    if (retParam != null && retParam.indexOf(ImdstDefine.keyNodeKeyCalcSuccessStr) == 0) {

                        mainNodeSave = true;
                    } else {

                        // 論理的に登録失敗
                        super.setDeadNode(nodeName + ":" + nodePort, 3, null);
                        logger.error("calcValue Logical Error Node =["  + nodeName + ":" + nodePort + "] retParam=[" + retParam + "]" + " Connectoer=[" + keyNodeConnector.connectorDump() + "]");
                    }

                    // 使用済みの接続を戻す
                    super.addKeyNodeCacheConnectionPool(keyNodeConnector);

                } catch (SocketException se) {

                    //se.printStackTrace();
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                    super.setDeadNode(nodeName + ":" + nodePort, 5, se);
                    logger.debug(se);
                } catch (IOException ie) {

                    //ie.printStackTrace();
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                    super.setDeadNode(nodeName + ":" + nodePort, 6, ie);
                    logger.debug(ie);
                } catch (Exception ee) {

                    //ee.printStackTrace();
                    if (keyNodeConnector != null) {
                        keyNodeConnector.close();
                        keyNodeConnector = null;
                    }
                    super.setDeadNode(nodeName + ":" + nodePort, 7, ee);
                    logger.debug(ee);
                }

            }

            // ノードへの保存状況を確認
            if (mainNodeSave == false) {
                retParam = null;
            }

        } catch (Exception e) {

            if (keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }
            retParam = null;
        } finally {

            // ノードの使用終了をマーク
            super.execNodeUseEnd(keyNodeFullName);

            // 返却地値をパースする
            if (retParam != null) {
                retParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
            } 
        }

        return retParams;
    }


    /**
     * KeyNodeに対してデータLockを依頼する.<br>
     * 
     * 
     * @param transactionManagerName TransactonManagerの名前(IPなど)
     * @param transactionManagerPort TransactonManagerのアクセスポート番号
     * @param key 対象Key
     * @param transactionCode TransactionCode
     * @param lockingTime Lock維持時間(この時間を過ぎると自動的に解除される。0は無限)(秒)
     * @param lockingWaitTime Lock取得までの待ち時間(Lockを取得するのに待つ時間。0は無限)(秒)
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] lockKeyNodeValue(String transactionManagerName, String transactionManagerPort, String key, String transactionCode, String lockingTime, String lockingWaitTime) throws BatchException {

        KeyNodeConnector keyNodeConnector = null;

        String retParam = null;
        String[] retParams = null;

        try {
            if (!transactionMode) {
                retParams = new String[2];
                retParams[0] = "30";
                retParams[1] = "false";
            }


            // KeyNodeとの接続を確立
            keyNodeConnector = this.createTransactionManagerConnection(transactionManagerName, transactionManagerPort, transactionManagerName + ":" + transactionManagerPort);


            // 接続結果と、現在の保存先状況で処理を分岐
            if (keyNodeConnector != null) {
                try {

                    // Key値でLockを取得
                    StringBuilder buf = new StringBuilder(ImdstDefine.stringBufferSmallSize);
                    buf.append("30");
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(this.stringCnv(key));                // Key値
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(transactionCode);                    // Transaction値
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(lockingTime);                        // lockingTime値
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(lockingWaitTime);                    // lockingWaitTime値

                    // 送信
                    keyNodeConnector.println(buf.toString());
                    keyNodeConnector.flush();

                    // 返却値取得
                    retParam = keyNodeConnector.readLine();
                } catch (SocketException se) {

                    logger.error("TransactionManager - Error " + se);
                } catch (IOException ie) {

                    // Nodeの通信失敗を記録
                    logger.error("TransactionManager - Error " + ie);
                }
            }

            // Lockの成功を判定
            if (retParam != null) {

                // splitは遅いので特定文字列で返却値が始まるかをチェックし始まる場合はLock成功
                if (retParam.indexOf(ImdstDefine.keyNodeLockingSuccessStr) == 0) {

                    // 成功
                    retParams = new String[3];
                    retParams[0] = "30";
                    retParams[1] = "true";
                    retParams[2] = transactionCode;
                } else {

                    // 失敗
                    retParams = new String[2];
                    retParams[0] = "30";
                    retParams[1] = "false";
                }
            } else {

                // 失敗
                retParams = new String[2];
                retParams[0] = "30";
                retParams[1] = "false";
            }
        } catch (BatchException be) {

            throw be;
        } catch (Exception e) {

            throw new BatchException(e);
        } 

        return retParams;
    }


    /**
     * KeyNodeに対してデータLockを解除依頼する.<br>
     * 
     * @param transactionManagerName TransactonManagerの名前(IPなど)
     * @param transactionManagerPort TransactonManagerのアクセスポート番号
     * @param key 対象Key
     * @param transactionCode TransactionCode
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] releaseLockKeyNodeValue(String transactionManagerName, String transactionManagerPort, String key, String transactionCode) throws BatchException {

        KeyNodeConnector keyNodeConnector = null;

        String retParam = null;
        String[] retParams = null;

        try {
            if (!transactionMode) {
                retParams = new String[2];
                retParams[0] = "31";
                retParams[1] = "false";
            }


            // KeyNodeとの接続を確立
            keyNodeConnector = this.createTransactionManagerConnection(transactionManagerName, transactionManagerPort, transactionManagerName + ":" + transactionManagerPort);


            // 接続結果と、現在の保存先状況で処理を分岐
            if (keyNodeConnector != null) {
                try {

                    // Key値でLockを取得
                    StringBuilder buf = new StringBuilder(ImdstDefine.stringBufferSmallSize);
                    buf.append("31");
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(this.stringCnv(key));                  // Key値
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(transactionCode);                      // Transaction値

                    // 送信
                    keyNodeConnector.println(buf.toString());
                    keyNodeConnector.flush();

                    // 返却値取得
                    retParam = keyNodeConnector.readLine();
                } catch (SocketException se) {

                    logger.error("TransactionManager - Error " + se);
                } catch (IOException ie) {

                    // Nodeの通信失敗を記録
                    logger.error("TransactionManager - Error " + ie);
                }
            }

            // Lock解除の成功を判定
            if (retParam != null) {

                // splitは遅いので特定文字列で返却値が始まるかをチェックし始まる場合はLock成功
                if (retParam.indexOf(ImdstDefine.keyNodeReleaseSuccessStr) == 0) {

                    // 成功
                    retParams = new String[3];
                    retParams[0] = "31";
                    retParams[1] = "true";
                    retParams[2] = transactionCode;
                } else {

                    // 失敗
                    retParams = new String[2];
                    retParams[0] = "31";
                    retParams[1] = "false";
                }
            } else {

                // 失敗
                retParams = new String[2];
                retParams[0] = "31";
                retParams[1] = "false";
            }
        } catch (BatchException be) {

            throw be;
        } catch (Exception e) {

            throw new BatchException(e);
        } 

        return retParams;
    }


    /**
     * KeyNodeに対してデータLockを依頼する.<br>
     * 
     * 
     * @param transactionManagerName TransactonManagerの名前(IPなど)
     * @param transactionManagerPort TransactonManagerのアクセスポート番号
     * @param key 対象Key
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] hasLockKeyNode(String transactionManagerName, String transactionManagerPort, String key) throws BatchException {

        KeyNodeConnector keyNodeConnector = null;

        String retParam = null;
        String[] retParams = null;

        try {
            if (!transactionMode) {
                retParams = new String[2];
                retParams[0] = "32";
                retParams[1] = "false";
            }


            // KeyNodeとの接続を確立
            keyNodeConnector = this.createTransactionManagerConnection(transactionManagerName, transactionManagerPort, transactionManagerName + ":" + transactionManagerPort);


            // 接続結果と、現在の保存先状況で処理を分岐
            if (keyNodeConnector != null) {
                try {

                    // Key値でLockを取得
                    StringBuilder buf = new StringBuilder(ImdstDefine.stringBufferSmallSize);
                    buf.append("32");
                    buf.append(ImdstDefine.keyHelperClientParamSep);
                    buf.append(this.stringCnv(key));               // Key値

                    // 送信
                    keyNodeConnector.println(buf.toString());
                    keyNodeConnector.flush();

                    // 返却値取得
                    retParam = keyNodeConnector.readLine();
                } catch (SocketException se) {

                    logger.error("TransactionManager - Error " + se);
                } catch (IOException ie) {

                    // Nodeの通信失敗を記録
                    logger.error("TransactionManager - Error " + ie);
                }
            }

            // Lockの成功を判定
            if (retParam != null) {

                // splitは遅いので特定文字列で返却値が始まるかをチェックし始まる場合はLock成功
                if (retParam.indexOf(ImdstDefine.hasKeyNodeLockSuccessStr) == 0) {

                    // 成功
                    retParams = retParam.split(ImdstDefine.keyHelperClientParamSep);
                } else {

                    // 失敗
                    retParams = new String[2];
                    retParams[0] = "32";
                    retParams[1] = "false";
                }
            } else {

                // 失敗
                retParams = new String[2];
                retParams[0] = "32";
                retParams[1] = "false";
            }
        } catch (BatchException be) {

            throw be;
        } catch (Exception e) {

            throw new BatchException(e);
        } 

        return retParams;
    }


    /**
     * KeyNodeの一時使用停止をマークする.<br>
     * 
     * @param keyNodeFullName データノードのフルネーム(IP,Port)
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] pauseKeyNodeUse(String keyNodeFullName) throws BatchException {

        String[] retParams = new String[3];

        try {

            // ノードの使用中断を要求
            super.setNodeWaitStatus(keyNodeFullName);
            while(true) {
                // 使用停止まで待機
                if(super.getNodeUseStatus(keyNodeFullName) == 0) break;
                Thread.sleep(50);
            }

            // 停止成功
            retParams[0] = "90";
            retParams[1] = "true";
            retParams[2] = "";

        } catch (Exception e) {
            throw new BatchException(e);
        }
        return retParams;
    }


    /**
     * KeyNodeの一時使用停止解除をマークする.<br>
     * 
     * @param keyNodeFullName データノードのフルネーム(IP,Port)
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] restartKeyNodeUse(String keyNodeFullName) throws BatchException {

        String[] retParams = new String[3];

        try {

            // ノードの一時使用停止解除を要求
            super.removeNodeWaitStatus(keyNodeFullName);

            // 再開成功
            retParams[0] = "91";
            retParams[1] = "true";
            retParams[2] = "";

        } catch (Exception e) {

            throw new BatchException(e);
        } 

        return retParams;
    }


    /**
     * KeyNodeの復旧をマークする.<br>
     * 
     * @param keyNodeFullName データノードのフルネーム(IP,Port)
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] deadKeyNode(String keyNodeFullName) throws BatchException {

        String[] retParams = null;

        try {

            StatusUtil.setDeadNode(keyNodeFullName);
            super.setDeadNode(keyNodeFullName, 9, null);
        } catch (Exception e) {

            logger.error("", e);
        } finally {
            retParams = new String[3];
            retParams[0] = "95";
            retParams[1] = "true";
            retParams[2] = "";
        }

        return retParams;
    }
    /**
     * KeyNodeの復旧をマークする.<br>
     * 
     * @param keyNodeFullName データノードのフルネーム(IP,Port)
     * @return String[] 結果
     * @throws BatchException
     */
    private String[] arriveKeyNode(String keyNodeFullName) throws BatchException {

        String[] retParams = new String[3];

        try {

            // ノードの復旧を要求
            super.setArriveNode(keyNodeFullName);

            // 再開成功
            retParams[0] = "92";
            retParams[1] = "true";
            retParams[2] = "";

        } catch (Exception e) {

            throw new BatchException(e);
        } 

        return retParams;
    }


    /**
     * KeyNodeとの接続を確立して返す.<br>
     * 接続が確立出来ない場合はエラー結果をログに残し、戻り値はnullとなる.<br>
     *
     * @param keyNodeName
     * @param keyNodePort
     * @param retryFlg キャッシュを一度破棄して再接続を行う
     * @return HashMap
     * @throws BatchException
     */
    private KeyNodeConnector createKeyNodeConnection(String keyNodeName, String keyNodePort, String keyNodeFullName, boolean retryFlg) throws BatchException {
        KeyNodeConnector keyNodeConnector = null;

        String connectionFullName = keyNodeFullName;
        Long connectTime = new Long(0);
        String connTimeKey = "time";

        boolean sockCheck = false;

        try {

            if (!super.isNodeArrival(connectionFullName)) {
                return null;
            }

            if (!retryFlg) {
                // 既にKeyNodeに対するコネクションが確立出来ている場合は使いまわす
                if (super.keyNodeConnectPool.containsKey(connectionFullName)) {
                    if((keyNodeConnector = (KeyNodeConnector)((ArrayBlockingQueue)super.keyNodeConnectPool.get(connectionFullName)).poll()) != null) {
                        if (!super.checkConnectionEffective(connectionFullName, keyNodeConnector.getConnetTime())) {
                            keyNodeConnector = null;
                        }
                    }
                } 


                // まだ接続が完了していない場合は接続処理続行
                // TODO:ConnectionPoolは一時休止中なのでコメントアウト
                /*if (keyNodeConnector == null)
                    // 新規接続
                    // 親クラスから既に接続済みの接続をもらう
                    keyNodeConnector = super.getActiveConnection(connectionFullName);
                */
            }

            // まだ接続が完了していない場合は接続処理続行
            if (keyNodeConnector == null) {
                // 接続が存在しない場合は自身で接続処理を行う
                keyNodeConnector = new KeyNodeConnector(keyNodeName, Integer.parseInt(keyNodePort), keyNodeFullName);
                keyNodeConnector.connect();
            }

            if(keyNodeConnector != null) {
                keyNodeConnector.initRetryFlg();
                try {
                    if(this.longReadTimeout) {
                        keyNodeConnector.setSoTimeout(ImdstDefine.nodeConnectionTimeout*2);
                    } else {
                        keyNodeConnector.setSoTimeout(ImdstDefine.nodeConnectionTimeout);
                    }
                } catch (Exception ee) {
                }
            }
        } catch (Exception e) {
            logger.error(connectionFullName + " " + e);

            if(keyNodeConnector != null) {
                keyNodeConnector.close();
                keyNodeConnector = null;
            }


            // 一度接続不慮が発生した場合はこのSESSIONでは接続しない設定とする
            super.setDeadNode(connectionFullName, 20, e);
        } 

        return keyNodeConnector;
    }


    /**
     * TransactionMangerとの接続を作成.<br>
     *
     * @param keyNodeName
     * @param keyNodePort
     * @param keyNodeFullName
     * @return HashMap
     * @throws BatchException
     */
    private KeyNodeConnector createTransactionManagerConnection(String keyNodeName, String keyNodePort, String keyNodeFullName) throws BatchException {
        return createKeyNodeConnection(keyNodeName, keyNodePort, keyNodeFullName, false);
    }


    /**
     * Clientとの接続を切断する.<br>
     *
     */
    private void closeClientConnect(PrintWriter pw, CustomReader br, Socket socket) {

        try {
            if(pw != null) {
                pw.close();
                pw = null;
            }

            if(br != null) {
                br.close();
                br = null;
            }

            if(socket != null) {
                socket.close();
                socket = null;
            }
        } catch (Exception e) {
            logger.error("", e);
        }
    }



    /**
     * 一貫性モードに合わせてパラメータ変更.<br>
     *
     */
    private void initConsistencyMode() {

        // 一貫性モードでreverseAccess値を変更
        // 中一貫性時は常にスレーブNodeからアクセス
        if (this.dataConsistencyMode == 1) {

            this.reverseAccess = true;
        } else if (this.dataConsistencyMode == 2) {
        
            // 強一貫性時は常にメインNodeからアクセス
            this.reverseAccess = false;
        }
    }


    /**
     * データノードからの結果文字列を結果値と更新時間の2つに分解する.<br>
     *
     * @param targetStr 対象値
     * @return String[] 結果 [0]=取り出した結果文字列(複数Valueの場合は、Key:Value;Key:Value;....), [1]=更新時間(登録されていない場合は-1)(複数Valueの場合は、Time;Time;...)
     */
    private String[] dataConvert4Consistency(String targetStr) {
        boolean multiResult = false;

        String[] ret = new String[2];
        ret[0] = null;
        ret[1] = "-1";


        if (targetStr != null) {
            // 複数のValue値を扱っているかをチェック
            if (targetStr.indexOf(ImdstDefine.setTimeParamSep) < targetStr.indexOf(";")) multiResult = true;

            if (!multiResult) {
                String[] setTimeSplitRet = targetStr.split(ImdstDefine.setTimeParamSep);

                if(setTimeSplitRet.length > 1) {

                    ret[0] = setTimeSplitRet[0];

                    if (setTimeSplitRet[1].trim().length() > 0) {

                        ret[1] = setTimeSplitRet[1];
                    }
                } else {

                    ret[0] = setTimeSplitRet[0];
                }
            } else {

                // MultiValue
                String[] multiResultList = targetStr.split(";");

                StringBuilder valueBuf = new StringBuilder(1024);
                StringBuilder timeBuf = new StringBuilder(1024);
                String valSep = "";
                String timeSep = "";
                for (int idx = 0; idx < multiResultList.length; idx++) {
                    String[] setTimeSplitRet = multiResultList[idx].split(ImdstDefine.setTimeParamSep);

                    if(setTimeSplitRet.length > 1) {
                        valueBuf.append(valSep);
                        valueBuf.append(setTimeSplitRet[0]);

                        if (setTimeSplitRet[1].trim().length() > 0) {

                            timeBuf.append(timeSep);
                            timeBuf.append(setTimeSplitRet[1]);
                            timeSep = ";";
                        }
                    } else {

                        valueBuf.append(valSep);
                        valueBuf.append(setTimeSplitRet[0]);
                    }
                    valSep = ";";
                }
                ret[0] = valueBuf.toString();
                ret[1] = timeBuf.toString();
            }
        }
        return ret;
    }


    /**
     * 強一貫性の処理.<br>
     * 
     * @param execRetParams
     *
     */
    private String[] strongConsistencyDataConvert(String[] execRetParams, String[] mainNodeRetParam, String[] subNodeRetParam, String type, boolean returnVersion) {
        String[] checkConsistencyRetMain = null;
        String[] checkConsistencyRetSub = null;

        String[] retParams = execRetParams;


        if (mainNodeRetParam != null && mainNodeRetParam.length > 1 && mainNodeRetParam[1].equals("true")) {

            checkConsistencyRetMain = dataConvert4Consistency(mainNodeRetParam[2]);
        } else if (mainNodeRetParam != null && mainNodeRetParam.length > 1 && mainNodeRetParam[1].equals("false")) {

            checkConsistencyRetMain = new String[2];
            checkConsistencyRetMain[0] = null;
            checkConsistencyRetMain[1] = "-1";
        }

        if (subNodeRetParam != null && subNodeRetParam.length > 1 && subNodeRetParam[1].equals("true")) {

            checkConsistencyRetSub = dataConvert4Consistency(subNodeRetParam[2]);
        } else if (subNodeRetParam != null && subNodeRetParam.length > 1 && subNodeRetParam[1].equals("false")) {

            checkConsistencyRetSub = new String[2];
            checkConsistencyRetSub[0] = null;
            checkConsistencyRetSub[1] = "-1";
        }


        if (checkConsistencyRetMain != null && checkConsistencyRetSub != null) {

            if (checkConsistencyRetMain[0] == null && checkConsistencyRetSub[0] == null) {

                retParams = new String[2];
                retParams[0] = type;
                retParams[1] = "false";
            } else if (Long.parseLong(checkConsistencyRetMain[1]) >= Long.parseLong(checkConsistencyRetSub[1])) {

                if (!returnVersion) {
                    retParams = new String[3];
                } else {
                    retParams = new String[4];
                }
                retParams[0] = type;
                retParams[1] = "true";
                retParams[2] = checkConsistencyRetMain[0];

                if (returnVersion)
                    retParams[3] = checkConsistencyRetMain[1];

            } else {

                if (!returnVersion) {
                    retParams = new String[3];
                } else {
                    retParams = new String[4];
                }

                retParams[0] = type;
                retParams[1] = "true";
                retParams[2] = checkConsistencyRetSub[0];

                if (returnVersion)
                    retParams[3] = checkConsistencyRetSub[1];

            }
        } else if (checkConsistencyRetMain != null) {

            if (checkConsistencyRetMain[0] == null) {

                retParams[1] = "false";
            } else {

                if (returnVersion) {
                    String[] workRet  =new String[4];
                    workRet[0] = retParams[0];
                    workRet[1] = retParams[1];
                    workRet[2] = retParams[2];
                    workRet[3] = checkConsistencyRetMain[1];
                    retParams = workRet;
                }

                retParams[2] = checkConsistencyRetMain[0];
            }
        } else {

            if (checkConsistencyRetSub[0] == null) {
                retParams[1] = "false";
            } else {
                if (returnVersion) {

                    String[] workRet  =new String[4];
                    workRet[0] = retParams[0];
                    workRet[1] = retParams[1];
                    workRet[2] = retParams[2];
                    workRet[3] = checkConsistencyRetSub[1];
                    retParams = workRet;
                }

                retParams[2] = checkConsistencyRetSub[0];
            }
        }
        return retParams;
    }


    // IsolationMode用
    private String encodeIsolationConvert(String str) {
        StringBuilder isolationBuffer = new StringBuilder(ImdstDefine.stringBufferSmallSize);
        if (this.isolationMode) {

            isolationBuffer.delete(0, Integer.MAX_VALUE);

            if (str != null && StatusUtil.isIsolationEncodeTarget(str)) {
                isolationBuffer.append(StatusUtil.getIsolationPrefix());
                isolationBuffer.append(str);
                return isolationBuffer.toString();
            } else {
                return str;
            }
        } else {
            return str;
        }
    }


    // IsolationMode用
    private String decodeIsolationConvert(String str) {

        if (this.isolationMode) {


            if (str != null) {
                
                return str.substring(this.isolationPrefixLength);
            } else {
                return str;
            }
        } else {
            return str;
        }
    }


    // 文字列変換メソッド
    private String stringCnv(String str) {
        return str;
        //return str.hashCode();
    }


    // キー値の長さをチェック
    private boolean checkKeyLength(String key) {
        if (key == null) return false;
        if (key.length() >= ImdstDefine.saveKeyMaxSize) return false;
        return true;
    }

    // Value値の長さをチェック
    private boolean checkValueLength(String value) {
        if (value == null) return false;
        if (value.length() >= new Double(ImdstDefine.saveDataMaxSize * 1.38).intValue()) return false;
        return true;
    }
}