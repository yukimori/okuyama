package test.junit.huge;

import static org.junit.Assert.*;
import okuyama.imdst.client.OkuyamaClient;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import test.junit.MethodTestHelper;

/**
 * 巨大データに対するlistLPopメソッドのテスト。
 * 
 * @author T.Okuyama
 * @license GPL(Lv3)
 *
 */
public class LPopMethodHugeTest {

	@Rule
	public ExpectedException thrown = ExpectedException.none();

	private static MethodTestHelper helper = new MethodTestHelper();

	private OkuyamaClient okuyamaClient;
	
	private String listName;
	
	private String listData;

	@Before
	public void setUp() throws Exception {
		LPopMethodHugeTest.helper.init();
		LPopMethodHugeTest.helper.initBigTestData();
		// okuyamaに接続
		this.okuyamaClient = LPopMethodHugeTest.helper.getConnectedOkuyamaClient();
		// List構造準備
		this.listName = LPopMethodHugeTest.helper.createTestDataKey(false, 0);
		this.listData = LPopMethodHugeTest.helper.createTestDataKey(true, 1);
		String[] ret = this.okuyamaClient.createListStruct(this.listName);
		assertEquals(ret[0], "true");
		ret = this.okuyamaClient.listLPush(this.listName, this.listData);
		assertEquals(ret[0], "true");
	}

	@After
	public void tearDown() throws Exception {
		LPopMethodHugeTest.helper.deleteAllData();
		this.okuyamaClient.close();
	}
	
	@Test
	public void リストの先頭から巨大データを取り出す() throws Exception {
		String[] ret = okuyamaClient.listLPop(this.listName);
		assertEquals(ret[0], "true");
		assertEquals(ret[1], this.listData);
	}

}
