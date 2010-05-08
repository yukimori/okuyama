package org.imdst.util;

/**
 * 定数をまとめる.<br>
 *
 *
 * @author T.Okuyama
 * @license GPL(Lv3)
 */
public class ImdstDefine {


    /* -- KeyMapファイルに関係する定数 ----------                        */
    // KeyNodeのWorkファイルでのセパレータ
    //public static final String keyWorkFileSep = "#imdst7386#";
    public static final String keyWorkFileSep = ",";

    // KeyNodeのWorkファイルの文字コード
    public static final String keyWorkFileEncoding = "UTF-8";

    // KeyNodeのWorkKeyファイルの終点文字列
    //public static final String keyWorkFileEndPointStr = "#imdstEndWFP8574";
    public static final String keyWorkFileEndPointStr = ";";


    /* --  クライアントとの転送内容に使用する定数 ----------              */
    // クライアントとの文字コード
    public static final String keyHelperClientParamEncoding = "UTF-8";

    // クライアントからのリクエスト文字列のセパレータ
    public static final String keyHelperClientParamSep = ",";

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

    // データノードに対するKeyデータ新規登録時に既に値が登録されていた場合のエラーメッセージ
    public static final String keyNodeKeyNewRegistErrMsg = "NG:Data has already been registered";

    // データノードに対するKeyデータ削除時に成功した場合の返却文字列の先頭部分
    public static final String keyNodeKeyRemoveSuccessStr = "5,true";

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

    // memcacheモード時の命令コマンドのget命令文字
    public static final String memcacheExecuteMethodGet = "get";

    // memcacheのSet時の32bit値をValue値に連結する場合のセパレータ
    public static final String memcacheUnique32BitSep = ":";

    // memcacheモード時の命令コマンドのset命令の成功結果文字
    public static final String memcacheMethodReturnSuccessSet = "STORED";

    // memcacheモード時の命令コマンドのset命令の成功結果文字
    public static final String memcacheMethodRetrunServerError = "SERVER_ERROR";

    // memcacheモード時の命令コマンドのadd命令の登録エラー結果文字
    public static final String memcacheMethodReturnErrorAdd = "NOT_STORED";

    // memcacheモード時の命令コマンドのdelete命令の削除成功結果文字
    public static final String memcacheMethodReturnSuccessDelete = "DELETED";

    // memcacheモード時の命令コマンドのdelete命令の削除失敗(存在しない)結果文字
    public static final String memcacheMethodReturnErrorDelete = "NOT_FOUND";

    // memcacheモード時の命令コマンドの共通エラー文字列
    public static final String memcacheMethodReturnErrorComn = "ERROR";


    /* --  通信時の固定文字列系定数  ----------                           */
    // クラインが接続を切断する際に通知する文字列
    public static final String imdstConnectExitRequest = "(&imdst9999&)";

    // 全てのKeyMapObjectファイルをKey=Valueの形式で接続した場合のデータ区切り文字
    public static final String imdstConnectAllDataSendDataSep = ";";

    /* --  設定ファイルの固定文字列系定数  ----------                     */
    public static final String Prop_KeyMapNodesRule = "KeyMapNodesRule";
    public static final String Prop_KeyMapNodesInfo = "KeyMapNodesInfo";
    public static final String Prop_SubKeyMapNodesInfo = "SubKeyMapNodesInfo";
    public static final String Prop_LoadBalanceMode = "LoadBalanceMode";
    public static final String Prop_TransactionMode = "TransactionMode";
    public static final String Prop_TransactionManagerInfo = "TransactionManagerInfo";
    public static final String Prop_MainMasterNodeMode = "MainMasterNodeMode";
    public static final String Prop_SlaveMasterNodes = "SlaveMasterNodes";


    public static final String dataNodeParamKey_1 = "dataNodeNameList";
    public static final String dataNodeParamKey_2 = "dataNodePortList";
    public static final String dataNodeParamKey_3 = "dataSubNodeNameList";
    public static final String dataNodeParamKey_4 = "dataSubNodePortList";
    public static final String dataNodeParamKey_5 = "keyMapNodeInfo";


    /* -- ここからプログラム内固定文字列系(Mapのキーとか)  ----------      */
    public static final String keyNodeSocketKey = "socket";
    public static final String keyNodeStreamWriterKey = "stream_writer";
    public static final String keyNodeStreamReaderKey = "stream_reader";
    public static final String keyNodeWriterKey = "writer";
    public static final String keyNodeReaderKey = "reader";
    public static final String keyNodeConnectionMapKey = "map";
    public static final String keyNodeConnectionMapTime = "time";


    /* -- プログラム規定数値 ----------------------------------------      */
    // 保存出来る、Key、Tag、Valueの最大長
    // データ保存の場合は以下の数値がお勧め
    public static final int saveDataMaxSize = 2560;
    // ファイル保存の場合は以下の数値がお勧め(512KB)
    //public static final int saveDataMaxSize =524288;

    // Node間のコネクションタイムアウト時間(ミリ秒)
    public static final int nodeConnectionTimeout = 60000;

   // Recoverのコネクションタイムアウト時間(ミリ秒)
    public static final int recoverConnectionTimeout = 60000 * 60;

}