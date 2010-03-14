package org.imdst.job;

import java.io.*;
import java.net.*;

import org.batch.lang.BatchException;
import org.batch.job.AbstractJob;
import org.batch.job.AbstractHelper;
import org.batch.job.IJob;
import org.batch.util.ILogger;
import org.batch.util.LoggerFactory;
import org.imdst.util.KeyMapManager;
import org.imdst.util.StatusUtil;
import org.imdst.util.ImdstDefine;
import org.imdst.util.DataDispatcher;
import org.imdst.util.StatusUtil;

/**
 * MasterNode、自身でポートを上げて待ち受ける<br>
 * クライアントからの要求をHelperに依頼する.<br>
 *
 * @author T.Okuyama
 * @license GPL(Lv3)
 */
public class MasterManagerJob extends AbstractJob implements IJob {

    private int portNo = 5554;

    // サーバーソケット
    ServerSocket serverSocket = null;

    // 過去ルール
    private int[] oldRules = null;

	// ロードバランス設定
	private boolean loadBalance = false;
	private boolean blanceMode = false;

    /**
     * Logger.<br>
     */
    private static ILogger logger = LoggerFactory.createLogger(MasterManagerJob.class);

    // 初期化メソッド定義
    public void initJob(String initValue) {
        logger.debug("MasterManagerJob - initJob - start");

        this.portNo = Integer.parseInt(initValue);

        logger.debug("MasterManagerJob - initJob - end");
    }

    // Jobメイン処理定義
    public String executeJob(String optionParam) throws BatchException {
        logger.debug("MasterManagerJob - executeJob - start");
        String ret = SUCCESS;
        Object[] helperParams = null;
        try{

            // KeyMapNodeの情報を初期化
            this.parseAllNodesInfo();

			// ロードバランス設定
			loadBalance = new Boolean((String)super.getPropertiesValue(ImdstDefine.Prop_LoadBalanceMode)).booleanValue();

            // サーバソケットの生成
            this.serverSocket = new ServerSocket(this.portNo);
            // 共有領域にServerソケットのポインタを格納
            super.setJobShareParam(super.getJobName() + "_ServeSocket", this.serverSocket);

            Socket socket = null;

            while (true) {
                if (StatusUtil.getStatus() == 1 || StatusUtil.getStatus() == 2) break;
                try {

                    // クライアントからの接続待ち
                    socket = serverSocket.accept();

					int paramSize = 2;
					if (loadBalance) paramSize = 3;

                    helperParams = new Object[paramSize];
                    helperParams[0] = socket;
                    helperParams[1] = this.oldRules;
					if (loadBalance) helperParams[2] = new Boolean(blanceMode);
                    super.executeHelper("MasterManagerHelper", helperParams);

					if (blanceMode) {
						blanceMode = false;
					} else {
						blanceMode = true;
					}
                } catch (Exception e) {
                    if (StatusUtil.getStatus() == 2) {
                        logger.info("MasterManagerJob - executeJob - ServerEnd");
                        break;
                    }
                    logger.error(e);
                }
            }
        } catch(Exception e) {
            logger.error("MasterManagerJob - executeJob - Error", e);
            throw new BatchException(e);
        }

        //logger.debug("MasterManagerJob - executeJob - end");
        return ret;
    }

    /**
     * KeyMapNodes,DataNodesの情報をパースする<br>
     * <br>
     * 以下の要素を設定する.<br>
     * KeyMapNodesRule=ルール値(2,9,99,999)<br>
     * KeyMapNodesInfo=Keyノードの設定(KeyNodeName1:11111, KeyNodeName2:22222)<br>
     * SubKeyMapNodesInfo=Keyノードの設定(KeyNodeName1:11111, KeyNodeName2:22222)<br>
     * <br>
     * 記述の決まり.<br>
     */
    private void parseAllNodesInfo() {
        String[] mainKeyNodes = null;
        String[] subKeyNodes = new String[0];
        String[] allNodeInfos = null;
        int allNodeCounter = 0;


        String keyMapNodesStr = super.getPropertiesValue(ImdstDefine.Prop_KeyMapNodesInfo);
        String subKeyMapNodesStr = super.getPropertiesValue(ImdstDefine.Prop_SubKeyMapNodesInfo);
        String ruleStrProp = super.getPropertiesValue(ImdstDefine.Prop_KeyMapNodesRule);

        // ノード追加によりルールが変更されている可能性があるのパース
        // ルールは最新ルールが先頭に来るように設定される想定なので、先頭文字列を取得
        String[] ruleStrs = ruleStrProp.split(",") ;
        // 過去ルールを自身に保存
        if (ruleStrs.length > 1) {
            this.oldRules = new int[ruleStrs.length - 1];
            for (int i = 1; i < ruleStrs.length; i++) {
                this.oldRules[i - 1] = new Integer(ruleStrs[i].trim()).intValue();
            }
        }

        // DataDispatcher初期化
        DataDispatcher.init(ruleStrs[0], keyMapNodesStr, subKeyMapNodesStr);

        // StatusUtilを初期化
        mainKeyNodes = keyMapNodesStr.split(",");

        allNodeCounter = mainKeyNodes.length;

        if (subKeyMapNodesStr != null && !subKeyMapNodesStr.equals("")) {
            subKeyNodes = subKeyMapNodesStr.split(",");
            allNodeCounter = allNodeCounter + subKeyNodes.length;
        }

        allNodeInfos = new String[allNodeCounter];

        for (int i = 0; i < mainKeyNodes.length; i++) {
            allNodeInfos[i] = mainKeyNodes[i];
        }

        for (int i = 0; i < subKeyNodes.length; i++) {
            allNodeInfos[i + mainKeyNodes.length] = subKeyNodes[i];
        }

        StatusUtil.initNodeExecMap(allNodeInfos);
    }

}