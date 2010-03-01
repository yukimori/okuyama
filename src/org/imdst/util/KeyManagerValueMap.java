package org.imdst.util;

import java.util.*;
import java.io.*;

import org.batch.util.ILogger;
import org.batch.util.LoggerFactory;
import org.batch.lang.BatchException;
import org.imdst.util.StatusUtil;

/**
 * @author T.Okuyama
 * @license GPL(Lv3)
 */
public class KeyManagerValueMap extends HashMap implements Cloneable, Serializable {

    private boolean memoryMode = true;

    private transient FileOutputStream fos = null;
    private transient OutputStreamWriter osw = null;
    private transient BufferedWriter bw = null;
    private transient RandomAccessFile raf = null;
    private int lineCount = 0;
    private int oneDataLength = 2760;
    private int seekOneDataLength = (2760);


    public KeyManagerValueMap(int size) {
        super(size);
    }

    /**
     * 本メソッドは使用前に必ず呼び出す<br>
     * Objectに書き出した後でも必須
     */
    public void initNoMemoryModeSetting(String lineFile) {
        try {
            memoryMode = false;
        System.out.println("KOKO");
            this.fos = new FileOutputStream(new File(lineFile), true);
            this.osw = new OutputStreamWriter(this.fos, ImdstDefine.keyWorkFileEncoding);
            this.bw = new BufferedWriter (osw);
            this.raf = new RandomAccessFile(new File(lineFile) , "r");

            FileInputStream fis = new FileInputStream(new File(lineFile));
            InputStreamReader isr = new InputStreamReader(fis , ImdstDefine.keyWorkFileEncoding);
            BufferedReader br = new BufferedReader(isr);
            int counter = 0;
            // !!!!!!!!!!!!!!! 現在1ファイルに改行はないのでここがおかしい ////////////////////////////
            while(br.readLine() != null){
                counter++;
            }
            this.lineCount = counter;
            br.close();
            isr.close();
            fis.close();
        } catch(Exception e) {
            e.printStackTrace();
            // 致命的
            StatusUtil.setStatusAndMessage(1, "KeyManagerValueMap - init - Error [" + e.getMessage() + "]");
        }
    }

    public Object get(Object key) {
        Object ret = null;
        if (memoryMode) {
            ret = super.get(key);
        } else {
            try {
            System.out.println("1111111111");
                int i = 0;
                byte[] buf = new byte[oneDataLength];

                int line = ((Integer)super.get(key)).intValue();
                
                // seek計算
                int seekInt = seekOneDataLength * (line - 1);

                raf.seek(seekInt);
                raf.read(buf,0,oneDataLength);
                for (; i < buf.length; i++) {
                    if (buf[i] == 35) break;
                }
            System.out.println("222222222222");
                ret = new String(buf, 0, i, ImdstDefine.keyWorkFileEncoding);
                System.out.println(ret);
            System.out.println("333333333333");
            } catch (Exception e) {
                e.printStackTrace();
                // 致命的
                StatusUtil.setStatusAndMessage(1, "KeyManagerValueMap - get - Error [" + e.getMessage() + "]");
                
            }
        }
        return ret;
    }


    public Object put(Object key, Object value) {
        Object ret = null;
        if (memoryMode) {
            ret = super.put(key, value);
        } else {
            try {
                StringBuffer writeStr = new StringBuffer();
                int valueSize = ((String)value).length();
                writeStr.append((String)value);

                // 渡されたデータが固定の長さ分ない場合は足りない部分を補う
                // 足りない文字列は固定の"#"で補う(35)
                byte[] appendDatas = new byte[oneDataLength - valueSize];

                for (int i = 0; i < appendDatas.length; i++) {
                    appendDatas[i] = 35;
                }

                writeStr.append(new String(appendDatas));
                // 書き込む行を決定
                this.lineCount++;
                this.bw.write(writeStr.toString());
                //this.bw.newLine();
                this.bw.flush();
                super.put(key, new Integer(lineCount));
            } catch (Exception e) {
                e.printStackTrace();
                // 致命的
                StatusUtil.setStatusAndMessage(1, "KeyManagerValueMap - get - Error [" + e.getMessage() + "]");
                
            }
        }
        return ret;
    }

}