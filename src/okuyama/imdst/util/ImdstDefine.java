package okuyama.imdst.util;

/**
 * 定数をまとめる.<br>
 *
 *
 * @author T.Okuyama
 * @license GPL(Lv3)
 */
public class ImdstDefine {

    public static final String okuyamaVersion = "VERSION okuyama-0.8.5";

    // -- KeyMapファイルに関係する定数 -------------------------------------------------
    // KeyNodeのWorkファイルでのセパレータ
    //public static final String keyWorkFileSep = "#imdst7386#";
    public static final String keyWorkFileSep = ",";

    // KeyNodeのWorkファイルの文字コード
    public static final String keyWorkFileEncoding = "UTF-8";

    // KeyNodeのWorkKeyファイルの終点文字列
    //public static final String keyWorkFileEndPointStr = "#imdstEndWFP8574";
    public static final String keyWorkFileEndPointStr = ";";


    // --  クライアントとの転送内容に使用する定数 -------------------------------------
    // クライアントとの文字コード
    public static final String keyHelperClientParamEncoding = "UTF-8";

    // クライアントからのリクエスト文字列のセパレータ
    public static final String keyHelperClientParamSep = ",";

    // クライアントからのリクエスト文字列のセパレータ
    public static final String setTimeParamSep = "!";

    // クラインととの連携文字列でのブランクを表す代行文字列
    public static final String imdstBlankStrData = "(B)";

    // Tag値の文字列の前方カッコ
    public static final String imdstTagStartStr = "{imdst_tag#9641";

    // Tag値の文字列の後方カッコ
    public static final String imdstTagEndStr = "1469#tag_imdst}";

    // Tag値でキーを登録する際にKey値を連結するのでその際のセパレータ文字列
    //public static final String imdstTagKeyAppendSep = "#imdst8417#";
    public static final String imdstTagKeyAppendSep = ":";

    // データノードに対するKeyデータ登録時に成功した場合の返却文字列の先頭部分
    public static final String keyNodeKeyRegistSuccessStr = "1,true";

    // データノードに対するTagデータ登録時に成功した場合の返却文字列の先頭部分
    public static final String keyNodeTagRegistSuccessStr = "3,true";

    // データノードに対するKeyデータ新規登録時に成功した場合の返却文字列の先頭部分
    public static final String keyNodeKeyNewRegistSuccessStr = "6,true";

    // データノードに対するバージョンチェック登録時に成功した場合の返却文字列の先頭部分
    public static final String keyNodeUpdateVersionCheckSuccessStr = "16,true";

    // データノードに対するKeyデータ新規登録時に既に値が登録されていた場合のエラーメッセージ
    public static final String keyNodeKeyNewRegistErrMsg = "NG:Data has already been registered";

    // データノードに対するKeyデータ登録時にバージョン値が変わっている場合のエラーメッセージ
    public static final String keyNodeKeyUpdatedErrMsg = "NG:Data has already been updated";

    // データノードに対するKeyデータ削除時に成功した場合の返却文字列の先頭部分
    public static final String keyNodeKeyRemoveSuccessStr = "5,true";

    // データノードに対するKeyデータ削除時に失敗した場合の返却文字列の先頭部分
    public static final String keyNodeKeyRemoveNotFoundStr = "5,false";

    // データノードに対するKeyデータ演算時に成功した場合の返却文字列の先頭部分
    public static final String keyNodeKeyCalcSuccessStr = "13,true";

    // データノードに対するLock取得に成功した場合の返却文字列の先頭部分
    public static final String keyNodeLockingSuccessStr = "30,true";

    // データノードに対するLock開放に成功した場合の返却文字列の先頭部分
    public static final String keyNodeReleaseSuccessStr = "31,true";

    // データノードに対するLock取得状況の確認の成否の返却文字列の先頭部分
    public static final String hasKeyNodeLockSuccessStr = "32,true";

    // memcacheモード時の命令コマンドの区切り文字
    public static final String memcacheExecuteMethodSep = " ";

    // memcacheモード時の命令コマンドのset命令文字
    public static final String memcacheExecuteMethodSet = "set";

    // memcacheモード時の命令コマンドのadd命令文字
    public static final String memcacheExecuteMethodAdd = "add";
    // memcacheモード時の命令コマンドのappend命令文字
    public static final String memcacheExecuteMethodAppend = "append";

    // memcacheモード時の命令コマンドのdelete命令文字
    public static final String memcacheExecuteMethodDelete = "delete";

    // memcacheモード時の命令コマンドのversion命令文字
    public static final String memcacheExecuteMethodVersion = "version";

    // memcacheモード時の命令コマンドのget命令文字
    public static final String memcacheExecuteMethodGet = "get";

    // memcacheモード時の命令コマンドのgets命令文字
    public static final String memcacheExecuteMethodGets = "gets";

    // memcacheモード時の命令コマンドのcas命令文字
    public static final String memcacheExecuteMethodCas = "cas";


    // memcacheのSet時の32bit値をValue値に連結する場合のセパレータ
    public static final String memcacheUnique32BitSep = ":";

    // memcacheモード時の命令コマンドのset命令の成功結果文字
    public static final String memcacheMethodReturnSuccessSet = "STORED";

    // memcacheモード時の命令コマンドのサーバエラー文字列
    public static final String memcacheMethodRetrunServerError = "SERVER_ERROR";

    // memcacheモード時の命令コマンドのadd命令の登録エラー結果文字
    public static final String memcacheMethodReturnErrorAdd = "NOT_STORED";

    // memcacheモード時の命令コマンドのcas命令の登録エラー結果文字
    public static final String memcacheMethodReturnErrorCas = "EXISTS";

    // memcacheモード時の命令コマンドのdelete命令の削除成功結果文字
    public static final String memcacheMethodReturnSuccessDelete = "DELETED";

    // memcacheモード時の命令コマンドのdelete命令の削除失敗(存在しない)結果文字
    public static final String memcacheMethodReturnErrorDelete = "NOT_FOUND";

    // memcacheモード時の命令コマンドの共通エラー文字列
    public static final String memcacheMethodReturnErrorComn = "ERROR";

    // Memcachedのsetコマンド変換用の部分文字列
    public static final String memcachedSetCommandPaddingStr =ImdstDefine.keyHelperClientParamSep + ImdstDefine.imdstBlankStrData + ImdstDefine.keyHelperClientParamSep + "0" + ImdstDefine.keyHelperClientParamSep; // // TransactionCode(0固定)

    // --  通信時の固定文字列系定数  --------------------------------------------------
    // クラインが接続を切断する際に通知する文字列
    public static final String imdstConnectExitRequest = "(&imdst9999&)";

    // 全てのKeyMapObjectファイルをKey=Valueの形式で接続した場合のデータ区切り文字
    public static final String imdstConnectAllDataSendDataSep = ";";


    // --  設定ファイルの固定文字列系定数  ---------------------------------------------
    public static final String Prop_KeyMapNodesRule = "KeyMapNodesRule";
    public static final String Prop_KeyMapNodesInfo = "KeyMapNodesInfo";
    public static final String Prop_SubKeyMapNodesInfo = "SubKeyMapNodesInfo";
    public static final String Prop_ThirdKeyMapNodesInfo = "ThirdKeyMapNodesInfo";
    public static final String Prop_LoadBalanceMode = "LoadBalanceMode";
    public static final String Prop_BalanceRatio = "BalanceRatio";
    public static final String Prop_TransactionMode = "TransactionMode";
    public static final String Prop_TransactionManagerInfo = "TransactionManagerInfo";
    public static final String Prop_MainMasterNodeMode = "MainMasterNodeMode";
    public static final String Prop_SlaveMasterNodes = "SlaveMasterNodes";
    public static final String Prop_SystemConfigMode = "SystemConfigMode";
    public static final String Prop_MyNodeInfo = "MyNodeInfo";
    public static final String Prop_MainMasterNodeInfo = "MainMasterNodeInfo";
    public static final String Prop_AllMasterNodeInfo = "AllMasterNodeInfo";
    public static final String Prop_DistributionAlgorithm = "DistributionAlgorithm";
    public static final String Prop_MasterNodeMaxConnectParallelExecution = "MasterNodeMaxConnectParallelExecution";
    public static final String Prop_MasterNodeMaxConnectParallelQueue = "MasterNodeMaxConnectParallelQueue";
    public static final String Prop_MasterNodeMaxAcceptParallelExecution = "MasterNodeMaxAcceptParallelExecution";
    public static final String Prop_MasterNodeMaxAcceptParallelQueue = "MasterNodeMaxAcceptParallelQueue";
    public static final String Prop_MasterNodeMaxWorkerParallelExecution = "MasterNodeMaxWorkerParallelExecution";
    public static final String Prop_MasterNodeMaxWorkerParallelQueue = "MasterNodeMaxWorkerParallelQueue";
    public static final String Prop_KeyNodeMaxConnectParallelExecution = "KeyNodeMaxConnectParallelExecution";
    public static final String Prop_KeyNodeMaxConnectParallelQueue = "KeyNodeMaxConnectParallelQueue";
    public static final String Prop_KeyNodeMaxAcceptParallelExecution = "KeyNodeMaxAcceptParallelExecution";
    public static final String Prop_KeyNodeMaxAcceptParallelQueue = "KeyNodeMaxAcceptParallelQueue";
    public static final String Prop_KeyNodeMaxWorkerParallelExecution = "KeyNodeMaxWorkerParallelExecution";
    public static final String Prop_KeyNodeMaxWorkerParallelQueue = "KeyNodeMaxWorkerParallelQueue";
    public static final String Prop_DataConsistencyMode = "DataConsistencyMode";
    public static final String Prop_IsolationMode = "IsolationMode";
    public static final String Prop_IsolationPrefix = "IsolationPrefix";
    public static final String Prop_ExecutionMethods = "ExecutionMethods";
    public static final String Prop_ConnectionAutoCloseTime = "connectionAutoCloseTime";
    public static final String Prop_MemoryMode = ".memoryMode";
    public static final String Prop_DataMemory = ".dataMemory";
    public static final String Prop_KeyMemory = ".keyMemory";
    public static final String Prop_KeySize = ".keySize";
    public static final String Prop_MemoryLimitSize = ".memoryLimitSize";
    public static final String Prop_VirtualStoreDirs = ".virtualStoreDirs";
    public static final String Prop_KeyStoreDirs = ".keyStoreDirs";


    // -- ここからプログラム内固定文字列系(Mapのキーとか)  -------------------------------
    public static final String dataNodeParamKey_1 = "dataNodeNameList";
    public static final String dataNodeParamKey_2 = "dataNodePortList";
    public static final String dataNodeParamKey_3 = "dataSubNodeNameList";
    public static final String dataNodeParamKey_4 = "dataSubNodePortList";
    public static final String dataNodeParamKey_5 = "dataThirdNodeNameList";
    public static final String dataNodeParamKey_6 = "dataThirdNodePortList";
    public static final String dataNodeParamKey_7 = "keyMapNodeInfo";

    public static final String okuyamaProtocol = "okuyama";
    public static final String memcacheProtocol = "memcache";
    public static final String memcachedProtocol = "memcached";
    public static final String memcache4datanodeProtocol = "memcache_datanode";
    public static final String memcached4datanodeProtocol = "memcached_datanode";
    public static final String memcache4proxyProtocol = "memcache_proxy";
    public static final String memcached4proxyProtocol = "memcached_proxy";


    public static final String keyNodeSocketKey = "socket";
    public static final String keyNodeStreamWriterKey = "stream_writer";
    public static final String keyNodeStreamReaderKey = "stream_reader";
    public static final String keyNodeWriterKey = "writer";
    public static final String keyNodeReaderKey = "reader";
    public static final int keyNodeConnectionMapKey = 0;
    public static final int keyNodeConnectionMapTime = 1;

    public static final String configModeFile = "file";
    public static final String configModeNode = "node";

    public static final String ConfigSaveNodePrefix = "MasterNode-MasterConfigSettingDataNodeSaveKeyPrefixString#112344%&987$#3# _ ";
    public static final String ConfigSaveNodePrefixEncodeStr = "TWFzdGVyTm9kZS1NYXN0ZXJDb25maWdTZXR0aW5nRGF0YU5vZGVTYXZlS2V5UHJlZml4U3RyaW5nIzExMjM0NCUmOTg3JCMzIyBfI";

    public static final int paramSocket       = 0;
    public static final int paramPw           = 1;
    public static final int paramBr           = 2;
    public static final int paramStart        = 3;
    public static final int paramLast         = 4;
    public static final int paramBalance      = 5;
    public static final int paramCheckCount   = 5;
    public static final int paramCheckCountMaster = 6;
    public static final int paramBis           = 7;

    public static final int dispatchModeModInt = 0;
    public static final int dispatchModeConsistentHashInt = 1;

    public static final String dispatchModeMod = "mod";
    public static final String dispatchModeConsistentHash = "consistenthash";

    public static final String addNode4ConsistentHashMode = "addNode4ConsistentHashMode";

    // Value値に含まれるメタ情報の区切り文字
    public static final String valueMetaColumnSep = "-";



    // ---- プログラム規定数値 -------------------------------------------------------------
    // 保存出来る、Key、Tag、Valueの最大長
    // Valueの最大長
    public static final int saveDataMaxSize = 1048576;

    // 大きいデータ保存する場合は以下の数値の用に最も保存する回数の多いサイズに合わせると
    // レスポンスが向上す。下記の場合は512KB
    //public static final int saveDataMaxSize =524288;

    // Key,Tagの最大長
    public static final int saveKeyMaxSize = 486;

    // 共通のデータファイルに書き出す最大サイズ
    public static final int dataFileWriteMaxSize = 1024 * 64;


    public static final int stringBufferSmallSize = 128;

    public static final int stringBufferSmall_2Size = 160;

    public static final int stringBufferMiddleSize = 512;

    public static final int stringBufferLargeSize = 1024;

    public static final int stringBufferLarge_2Size = 2048;

    public static final int stringBufferLarge_3Size = 8192;


    // ノードのDeadとする際のPing実行回数
    public static final int defaultDeadPingCount = 3;

    // クライアントのコネクションオープンタイムアウト時間(ミリ秒)
    public static final int clientConnectionOpenTimeout = 5000;

    // クライアントのコネクションタイムアウト時間(ミリ秒)
    public static final int clientConnectionTimeout = 60000 * 2;


    // Node間のコネクションオープンタイムアウト時間(ミリ秒)
    public static final int nodeConnectionOpenShortTimeout = 2000;

    public static final int nodeConnectiontReadShortTimeout = 2000;


    // Node間のコネクションオープンタイムアウト時間(ミリ秒)
    public static final int nodeConnectionOpenTimeout = 4000;
    //public static final int nodeConnectionOpenTimeout = 10000;

    // Node間のコネクションReadタイムアウト時間(ミリ秒)
    public static final int nodeConnectionTimeout = 8000;
    //public static final int nodeConnectionTimeout = 15000;

    public static final int nodeConnectionTimeout4RecoverMode = 60000 * 5;

    // Node間のコネクションオープンタイムアウト時間(PING)(ミリ秒)
    public static final int nodeConnectionOpenPingTimeout = 2500;

    // Node間のコネクションReadタイムアウト時間(PING)(ミリ秒)
    public static final int nodeConnectionPingTimeout = 4000;


   // Recoverのコネクションタイムアウト時間(ミリ秒)
    public static final int recoverConnectionTimeout = 60000 * 60;


    // ---- 分散アルゴリズム系 ---------------------------------------------------
    // 分散アルゴリズムにConsistentHashを使用した場合の仮想ノードの数
    public static final int consistentHashVirtualNode = 50;


    // ---- KeyMapManager系 ------------------------------------------------------
    // Key値の数とファイルの行数の差がこの数値を超えるとvacuumを行う候補となる
    // 行数と1行のデータサイズをかけると不要なデータサイズとなる
    public static final int vacuumStartLimit = 100000;

    // Key値の数とファイルの行数の差がこの数値を超えると強制的にvacuumを行う
    // 行数と1行のデータサイズをかけると不要なデータサイズとなる
    // vacuumStartLimit × (ImdstDefine.dataFileWriteMaxSize * 1.38) = 不要サイズ
    public static final int vacuumStartCompulsionLimit = 1000000;

    // Vacuum実行時に事前に以下のミリ秒の間アクセスがないと実行許可となる
    public static final int vacuumExecAfterAccessTime = 200;

    // トランザクションログをローテーションする際のサイズ(1.8GB)
    public static final long workFileChangeNewFileSize = 1610612736;

    // 保存データサイズの合計値演算設定
    // true:計算する
    // false:計算しない
    public static final boolean calcSizeFlg = false;

    // 有効期限切れデータバキューム実行指定
    public static boolean vacuumInvalidDataFlg = true;

    // 有効期限切れデータ削除チェックサイクル(単位:分)
    public static int startVaccumInvalidCount = 1;
//  public static int startVaccumInvalidCount = 30;

    // 有効期限切れのデータを実際に物理削除するまでの経過時間(ミリ秒)
//    public static final long invalidDataDeleteTime = 300000;
    public static final long invalidDataDeleteTime = 5000;



}