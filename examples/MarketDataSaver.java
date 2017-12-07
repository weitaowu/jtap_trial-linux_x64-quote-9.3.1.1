
import java.text.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import net.common.util.BufferUtil;
import net.jtap.*;

import com.wilddog.client.SyncReference;
import com.wilddog.client.WilddogSync;
import com.wilddog.wilddogcore.WilddogApp;
import com.wilddog.wilddogcore.WilddogOptions;

public class MarketDataSaver {

	static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	static final SimpleDateFormat marketDateFormat = new SimpleDateFormat("yyyy-MM-dd");

	static String dataFileSuffix;
	static AtomicInteger dataCount = new AtomicInteger();
	static volatile boolean requestStop;
	static File dataDir = new File("data");
	static final BlockingQueue<TapAPIQuoteWhole> marketDataQueue = new LinkedBlockingDeque<TapAPIQuoteWhole>();
	static final Map<String, BufferedWriter> dataWriterMap = new HashMap<String, BufferedWriter>();
	static final Map<String, TapAPIQuoteWhole> marketDataMap = new HashMap<String, TapAPIQuoteWhole>();

	private static class SaveThread implements Runnable {

//		@Override
		public void run() {
			StringBuilder line = new StringBuilder(512);
			while (!requestStop) {
				int queueLength = 0;
				TapAPIQuoteWhole field = null;
				try {
					field = marketDataQueue.take();
					queueLength = marketDataQueue.size();
				} catch (InterruptedException e) {
				}
				if (field == null)
					continue;
				try {
					String contractUID = field.Contract.Commodity.ExchangeNo + "."
							+ field.Contract.Commodity.CommodityNo + field.Contract.ContractNo1;
					BufferedWriter writer = dataWriterMap.get(contractUID);
					if (writer == null) {
						writer = new BufferedWriter(new OutputStreamWriter(
								new FileOutputStream(new File(dataDir, contractUID + "." + dataFileSuffix), true),
								"UTF-8"));
						writer.write("\n");
						dataWriterMap.put(contractUID, writer);
					}
					line.setLength(0);

					line.append(field.DateTimeStamp).append(",").append(field.Contract.Commodity.ExchangeNo).append(",")
							.append(field.Contract.Commodity.CommodityNo + field.Contract.ContractNo1).append(",")
							.append(field.QTotalQty).append(",").append(field.QLastQty).append(",")
							.append(price2str(field.QLastPrice)).append("\n");
					writer.write(line.toString());
					if (queueLength < 100)
						writer.flush();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			System.out.println("DataSaver Thread exiting...");
			for (BufferedWriter writer : dataWriterMap.values()) {
				try {
					writer.flush();
					writer.close();
				} catch (Exception e) {
				}
			}
			dataWriterMap.clear();
		}
	}

	private static class ThreadFuncWilddogWrite implements Runnable {

//		@Override
		public void run() {
			// 初始化野狗
			WilddogOptions options = new WilddogOptions.Builder().setSyncUrl("https://wd0980993345vffczg.wilddogio.com")
					.build();
			WilddogApp.initializeApp(options);
			SyncReference ref = WilddogSync.getInstance().getReference();
			
			Map<String, Object> rootMap = new HashMap<String, Object>();			
			while (!requestStop) {
				// sleep 0.5s
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				Collection<TapAPIQuoteWhole> c = marketDataMap.values();
				Iterator<TapAPIQuoteWhole> i = c.iterator();
				while (i.hasNext()) {
					TapAPIQuoteWhole info = (TapAPIQuoteWhole) i.next();
//					System.out.println(i.next());
					
					// 合约子树
					Map<String, Object> childrenMap = new HashMap<String, Object>();
//					childrenMap.put("Contract",info.Contract);                    ///< 合约
//					childrenMap.put("CurrencyNo",info.CurrencyNo);                ///< 币种编号
//					childrenMap.put("TradingState",info.TradingState);            ///< 交易状态。1,集合竞价;2,集合
					childrenMap.put("DateTimeStamp",info.DateTimeStamp);          			 ///< 时间戳 
					childrenMap.put("QPreClosingPrice",price2str(info.QPreClosingPrice));    ///< 昨收盘价
					childrenMap.put("QPreSettlePrice",price2str(info.QPreSettlePrice));      ///< 昨结算价
					childrenMap.put("QPrePositionQty",qty2str(info.QPrePositionQty));      	 ///< 昨持仓量
					childrenMap.put("QOpeningPrice",price2str(info.QOpeningPrice));          ///< 开盘价
					childrenMap.put("QLastPrice",price2str(info.QLastPrice));                ///< 最新价
					childrenMap.put("QHighPrice",price2str(info.QHighPrice));                ///< 最高价
					childrenMap.put("QLowPrice",price2str(info.QLowPrice));                  ///< 最低价
					childrenMap.put("QHisHighPrice",price2str(info.QHisHighPrice));          ///< 历史最高价
					childrenMap.put("QHisLowPrice",price2str(info.QHisLowPrice));            ///< 历史最低价
					childrenMap.put("QLimitUpPrice",price2str(info.QLimitUpPrice));          ///< 涨停价
					childrenMap.put("QLimitDownPrice",price2str(info.QLimitDownPrice));      ///< 跌停价
					childrenMap.put("QTotalQty",qty2str(info.QTotalQty));                  	 ///< 当日总成交量
					childrenMap.put("QTotalTurnover",price2str(info.QTotalTurnover));        ///< 当日成交金额
					childrenMap.put("QPositionQty",qty2str(info.QPositionQty));            	 ///< 持仓量
					childrenMap.put("QAveragePrice",price2str(info.QAveragePrice));          ///< 均价
					childrenMap.put("QClosingPrice",price2str(info.QClosingPrice));          ///< 收盘价
					childrenMap.put("QSettlePrice",price2str(info.QSettlePrice));            ///< 结算价
					childrenMap.put("QLastQty",qty2str(info.QLastQty));                    	 ///< 最新成交量
					childrenMap.put("QBidPrice",info.QBidPrice[0]);          ///< 买价1-20档
					childrenMap.put("QBidQty",info.QBidQty[0]);              ///< 买量1-20档
					childrenMap.put("QAskPrice",info.QAskPrice[0]);          ///< 卖价1-20档
					childrenMap.put("QAskQty",info.QAskQty[0]);              ///< 卖量1-20档
//					childrenMap.put("QBidPrice[20]",info.QBidPrice[20]);          ///< 买价1-20档
//					childrenMap.put("QBidQty[20]",info.QBidQty[20]);              ///< 买量1-20档
//					childrenMap.put("QAskPrice[20]",info.QAskPrice[20]);          ///< 卖价1-20档
//					childrenMap.put("QAskQty[20]",info.QAskQty[20]);              ///< 卖量1-20档
					childrenMap.put("QImpliedBidPrice",price2str(info.QImpliedBidPrice));    ///< 隐含买价
					childrenMap.put("QImpliedBidQty",qty2str(info.QImpliedBidQty));        	 ///< 隐含买量
					childrenMap.put("QImpliedAskPrice",price2str(info.QImpliedAskPrice));    ///< 隐含卖价
					childrenMap.put("QImpliedAskQty",qty2str(info.QImpliedAskQty));        	 ///< 隐含卖量
					childrenMap.put("QPreDelta",price2str(info.QPreDelta));                  ///< 昨虚实度
					childrenMap.put("QCurrDelta",price2str(info.QCurrDelta));                ///< 今虚实度
					childrenMap.put("QInsideQty",qty2str(info.QInsideQty));                	 ///< 内盘量
					childrenMap.put("QOutsideQty",qty2str(info.QOutsideQty));              	 ///< 外盘量
					childrenMap.put("QTurnoverRate",price2str(info.QTurnoverRate));          ///< 换手率
					childrenMap.put("Q5DAvgQty",qty2str(info.Q5DAvgQty));                  	 ///< 五日均量
					childrenMap.put("QPERatio",price2str(info.QPERatio));                    ///< 市盈率
					childrenMap.put("QTotalValue",price2str(info.QTotalValue));              ///< 总市值
					childrenMap.put("QNegotiableValue",price2str(info.QNegotiableValue));    ///< 流通市值
					childrenMap.put("QPositionTrend",price2str(info.QPositionTrend));        ///< 持仓走势
					childrenMap.put("QChangeSpeed",price2str(info.QChangeSpeed));            ///< 涨速
					childrenMap.put("QChangeRate",price2str(info.QChangeRate));              ///< 涨幅
					childrenMap.put("QChangeValue",price2str(info.QChangeValue));            ///< 涨跌值
					childrenMap.put("QSwing",price2str(info.QSwing));                        ///< 振幅
					childrenMap.put("QTotalBidQty",qty2str(info.QTotalBidQty));            	 ///< 委买总量
					childrenMap.put("QTotalAskQty",qty2str(info.QTotalAskQty));              ///< 委卖总量

					// 合约子树
					String pathString = info.Contract.Commodity.CommodityNo +"/"+ info.Contract.ContractNo1;
					rootMap.put(pathString, childrenMap);										
				}
				ref.updateChildren(rootMap);
			}
			System.out.println("Wilddog Write Thread exiting...");
			marketDataMap.clear();
		}
	}
	static final DecimalFormat millisecFormat = new DecimalFormat("000");

	private static String millisec2str(int millisec) {
		return millisecFormat.format(millisec);
	}

	static final DecimalFormat priceFormat = new DecimalFormat("###########0.0#");

	private static String price2str(double price) {
		if (price == Double.MAX_VALUE || price == Double.NaN)
			return "";
		return priceFormat.format(price);
	}
	
	static final DecimalFormat quantityFormat = new DecimalFormat("############0");

	private static String qty2str(long qty) {
		if (qty == Long.MAX_VALUE )
			return "";
		return quantityFormat.format(qty);
	}

	public static void main(String[] args) throws Throwable {
		
		System.out.println("Turbo Mode: " + BufferUtil.isTurboModeEnabled());
		{
			// 数据文件后缀年月日
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
			dataFileSuffix = dateFormat.format(new Date());
		}
		System.out.println("QuoteAPI version info: " + QuoteApi.GetVersion());
		
		//
		Properties configProps = loadConfig();
		String authCode = configProps.getProperty("tap.authCode");
		String quoteHost = configProps.getProperty("tap.quoteHost");
		int quotePort = Integer.parseInt(configProps.getProperty("tap.quotePort"));
		String userId = configProps.getProperty("tap.userId");
		String password = configProps.getProperty("tap.password");
		String ids[] = configProps.getProperty("marketDataSaver.instrumentIds").split(",");
		
		System.out.println("Connecting " + quoteHost + ":" + quotePort + " ... ");
		final QuoteApi mdApi = new QuoteApi(new TapAPIApplicationInfo(authCode, null));
		mdApi.setListener(new QuoteApiListener() {

//			@Override
			public void OnRspLogin(int errorCode, TapAPIQuotLoginRspInfo info) {
				System.out.println("DataSaver QuoteApi login: " + errorCode);
				if (errorCode != 0)
					requestStop = true;
			}

//			@Override
			public void OnAPIReady() {
				System.out.println("DataSaver QuoteApi ready.");
			}

//			@Override
			public void OnDisconnect(int reasonCode) {
				System.out.println("DataSaver disconnected.");
				requestStop = true;
			}

//			@Override
			public void OnRspChangePassword(int sessionID, int errorCode) {

			}

//			@Override
			public void OnRspQryCommodity(int sessionID, int errorCode, byte isLast, TapAPIQuoteCommodityInfo info) {
				// System.out.println("OnRspQryCommodity session "+sessionID+" error
				// "+errorCode+" "+info);
			}

//			@Override
			public void OnRspQryContract(int sessionID, int errorCode, byte isLast, TapAPIQuoteContractInfo info) {
				// System.out.println("OnRspQryContract session "+sessionID+" error
				// "+errorCode+" "+info);

			}

//			@Override
			public void OnRtnContract(TapAPIQuoteContractInfo info) {
				// TODO Auto-generated method stub

			}

//			@Override
			public void OnRspSubscribeQuote(int sessionID, int errorCode, byte isLast, TapAPIQuoteWhole info) {
				dataCount.incrementAndGet();
				try {
					marketDataQueue.put(info);
				} catch (InterruptedException e) {
				}
			}

//			@Override
			public void OnRspUnSubscribeQuote(int sessionID, int errorCode, byte isLast, TapAPIContract info) {
				System.out.println("OnRspUnSubscribeQuote session " + sessionID + " error " + errorCode + " : " + info);
			}

//			@Override
			public void OnRtnQuote(TapAPIQuoteWhole info) {
				dataCount.incrementAndGet();
				try {
					marketDataQueue.put(info);
					
					String contractUID = info.Contract.Commodity.ExchangeNo + "."
							+ info.Contract.Commodity.CommodityNo + info.Contract.ContractNo1;
					marketDataMap.put(contractUID, info);
				} catch (InterruptedException e) {
				}
			}

		});
		
		//
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				requestStop = true;
				for (BufferedWriter writer : dataWriterMap.values()) {
					try {
						writer.flush();
						writer.close();
					} catch (Exception e) {
					}
				}
				if (mdApi.isConnected()) {
					mdApi.Close();
					try {
						Thread.sleep(200);
					} catch (Exception e) {
					}
				}
			}
		});
		
		// 行情写野狗	
		System.out.println("启动线程行情写入云端...");
		ThreadFuncWilddogWrite threadFuncWilddogWrite = new ThreadFuncWilddogWrite();
		Thread writeThread = new Thread(threadFuncWilddogWrite);
		writeThread.setName("Market data wilddog write thread");
		writeThread.setDaemon(true);
		writeThread.start();
		
		// 行情写文件
		System.out.println("主力合约IDS to save: " + Arrays.asList(ids));
		dataDir.mkdirs();
		SaveThread saver = new SaveThread();
		Thread saverThread = new Thread(saver);
		saverThread.setName("Market data saver thread");
		saverThread.setDaemon(true);
		saverThread.start();

		TapAPIQuoteLoginAuth loginAuth = new TapAPIQuoteLoginAuth();
		loginAuth.UserNo = userId;
		loginAuth.Password = password;
		loginAuth.ISModifyPassword = JtapConstants.APIYNFLAG_NO;
		loginAuth.ISDDA = JtapConstants.APIYNFLAG_NO;
		mdApi.SyncLogin(quoteHost, quotePort, loginAuth);

		{
			// 查询服务器支持的品种
			System.out.println("Query commodity ...");
			TapAPIQuoteCommodityInfo[] infos = mdApi.SyncAllQryCommodity();
			for (int i = 0; i < infos.length; i++) {
				TapAPIQuoteCommodityInfo info = infos[i];
				StringBuilder builder = new StringBuilder();
				builder.append("Commodity " + info.Commodity.CommodityNo);
				if (info.CommodityName != null) {
					builder.append(" name ");
					if (info.CommodityEngName != null) {
						builder.append(info.CommodityEngName).append('/');
					}
					builder.append(info.CommodityName);
				}
				builder.append(" exchange ").append(info.Commodity.ExchangeNo);
				System.out.println(builder.toString());
			}
			System.out.flush();
		}

		for (int i = 0; i < ids.length; i++) {
			String[] parts = ids[i].split("\\.");
			String exchangeId = parts[0];
			String commodityNo = parts[1];
			String contractNo = parts[2];
			System.out.println(
					"Subscribe exchange " + exchangeId + " commodity " + commodityNo + " contract " + contractNo);
			TapAPIContract contract = new TapAPIContract();
			;
			contract.Commodity = new TapAPICommodity();
			contract.Commodity.ExchangeNo = exchangeId;
			contract.Commodity.CommodityNo = commodityNo;
			contract.Commodity.CommodityType = JtapConstants.TAPI_COMMODITY_TYPE_FUTURES;
			contract.ContractNo1 = contractNo;
			contract.CallOrPutFlag1 = JtapConstants.TAPI_CALLPUT_FLAG_NONE;
			contract.CallOrPutFlag2 = JtapConstants.TAPI_CALLPUT_FLAG_NONE;
			mdApi.SubscribeQuote(contract);
		}

		while (!requestStop) {
			Thread.sleep(60 * 1000);
			Date date = new Date();
			int count = dataCount.getAndSet(0);
			System.out.println(dateFormat.format(date) + " Market data receieved: " + count);
			Calendar calendar = Calendar.getInstance();
			calendar.setTime(date);
			int hour = calendar.get(Calendar.HOUR_OF_DAY);
			int minute = calendar.get(Calendar.MINUTE);
			if (count == 0 && hour >= 23 && minute >= 59) {
				System.out.println("Market is closed, DataSaver exiting...");
				requestStop = true;
			}
			if (requestStop)
				break;
		}

		Date date = new Date();
		int count = dataCount.getAndSet(0);
		if (count > 0)
			System.out.println(dateFormat.format(date) + " Market data receieved: " + count);

		mdApi.Close();
	}

	private static Properties loadConfig() throws IOException {
		Properties configProps = new Properties();
		InputStream is = ClassLoader.getSystemResourceAsStream("config.properties");
		if (is == null)
			is = ClassLoader.getSystemResourceAsStream("/config.properties");
		if (is == null) {
			File file = new File("config.properties");
			if (file.exists())
				is = new FileInputStream(file);
		}
		if (is == null) {
			File file = new File("examples/config.properties");
			if (file.exists())
				is = new FileInputStream(file);
		}
		if (is == null) {
			File file = new File("../examples/config.properties");
			if (file.exists())
				is = new FileInputStream(file);
		}
		if (is == null) {
			System.out.println("Unable to load config.properties from classpath or current directory.");
			System.exit(1);
		}
		configProps.load(is);
		is.close();

		return configProps;
	}

}
