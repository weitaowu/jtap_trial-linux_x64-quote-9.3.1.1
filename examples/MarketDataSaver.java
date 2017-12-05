import java.text.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import net.common.util.BufferUtil;
import net.jtap.*;

public class MarketDataSaver {

	static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	static final SimpleDateFormat marketDateFormat = new SimpleDateFormat("yyyy-MM-dd");

	static String dataFileSuffix;
	static AtomicInteger dataCount = new AtomicInteger();
	static volatile boolean requestStop;
	static File dataDir = new File("data");
	static final BlockingQueue<TapAPIQuoteWhole> marketDataQueue = new LinkedBlockingDeque<TapAPIQuoteWhole>();
	static final Map<String, BufferedWriter> dataWriterMap = new HashMap<String, BufferedWriter>();

	private static class SaveThread implements Runnable {

		@Override
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

	public static void main(String[] args) throws Throwable {
		System.out.println("Turbo Mode: " + BufferUtil.isTurboModeEnabled());
		{
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
			dataFileSuffix = dateFormat.format(new Date());
		}
		System.out.println("QuoteAPI version info: " + QuoteApi.GetVersion());
		Properties configProps = loadConfig();

		String authCode = configProps.getProperty("tap.authCode");
		String quoteHost = configProps.getProperty("tap.quoteHost");
		int quotePort = Integer.parseInt(configProps.getProperty("tap.quotePort"));
		String userId = configProps.getProperty("tap.userId");
		String password = configProps.getProperty("tap.password");
		String ids[] = configProps.getProperty("marketDataSaver.instrumentIds").split(",");
		System.out.println("Connecting " + quoteHost + ":" + quotePort + " ... ");

		final QuoteApi mdApi = new QuoteApi(new TapAPIApplicationInfo(authCode, null));
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

		mdApi.setListener(new QuoteApiListener() {

			@Override
			public void OnRspLogin(int errorCode, TapAPIQuotLoginRspInfo info) {
				System.out.println("DataSaver QuoteApi login: " + errorCode);
				if (errorCode != 0)
					requestStop = true;
			}

			@Override
			public void OnAPIReady() {
				System.out.println("DataSaver QuoteApi ready.");
			}

			@Override
			public void OnDisconnect(int reasonCode) {
				System.out.println("DataSaver disconnected.");
				requestStop = true;
			}

			@Override
			public void OnRspChangePassword(int sessionID, int errorCode) {

			}

			@Override
			public void OnRspQryCommodity(int sessionID, int errorCode, byte isLast, TapAPIQuoteCommodityInfo info) {
				// System.out.println("OnRspQryCommodity session "+sessionID+" error
				// "+errorCode+" "+info);
			}

			@Override
			public void OnRspQryContract(int sessionID, int errorCode, byte isLast, TapAPIQuoteContractInfo info) {
				// System.out.println("OnRspQryContract session "+sessionID+" error
				// "+errorCode+" "+info);

			}

			@Override
			public void OnRtnContract(TapAPIQuoteContractInfo info) {
				// TODO Auto-generated method stub

			}

			@Override
			public void OnRspSubscribeQuote(int sessionID, int errorCode, byte isLast, TapAPIQuoteWhole info) {
				dataCount.incrementAndGet();
				try {
					marketDataQueue.put(info);
				} catch (InterruptedException e) {
				}
			}

			@Override
			public void OnRspUnSubscribeQuote(int sessionID, int errorCode, byte isLast, TapAPIContract info) {
				System.out.println("OnRspUnSubscribeQuote session " + sessionID + " error " + errorCode + " : " + info);
			}

			@Override
			public void OnRtnQuote(TapAPIQuoteWhole info) {
				dataCount.incrementAndGet();
				try {
					marketDataQueue.put(info);
				} catch (InterruptedException e) {
				}
			}

		});

		System.out.println("IDS to save: " + Arrays.asList(ids));

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
			if (count == 0 && hour >= 23) {
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
