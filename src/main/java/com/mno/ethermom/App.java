package com.mno.ethermom;

import com.mno.ethermom.domain.CurrentStatsJsonResponse;
import com.mno.ethermom.domain.Worker;
import com.mno.ethermom.domain.WorkersJsonResponse;
import com.mno.ethermom.utils.ConfigUtil;
import com.mno.ethermom.utils.ConversionUtil;
import com.mno.ethermom.utils.HttpUtil;
import com.mno.ethermom.utils.messaging.MessagingUtil;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ethermine.org monitoring job. Sends out alert to IFTTT or Telegram channel
 * when hashrate dropped.
 * 
 * @author Min Naing Oo
 * @version 1.0
 *
 */
public class App {

	private static final File lockfile = new File("reported.lock");
	private static final File runningfile = new File("running.lock");

    public static void main( String[] args ) {

        System.out.println("Job started.");

		try {
			ConfigUtil.loadConfigs();

			if (!ConfigUtil.isEnabled()) {
				System.exit(0);
			}

			String walletAddr = ConfigUtil.getWalletAddress();
			if (walletAddr == null || walletAddr.isEmpty()) {
				throw new IllegalArgumentException("Wallet not set.");
			}

			if (ConfigUtil.getMode() == ConfigUtil.CONFIG_MODE_TOTAL) {

				double expectedHash = ConfigUtil.getExpectedHash(null);
				if (expectedHash <= 0) {
					throw new IllegalArgumentException("Invalid expected hashrate. Please set 'expectedHash' property");
				}

				CurrentStatsJsonResponse currentStatsRes = (CurrentStatsJsonResponse) HttpUtil.getJsonFromUrl(
						HttpUtil.getApiUrl(walletAddr, HttpUtil.API_FUNCTION_CURRENTSTATS),
						CurrentStatsJsonResponse.class);

				if (currentStatsRes == null || !HttpUtil.STATUS_OK.equals(currentStatsRes.getStatus())) {
					throw new Exception("API is not working.");
				}

				if (currentStatsRes.getData().getReportedHashrate() < expectedHash) {
					MessagingUtil.sendMessage("Reported hashrate is lower than expected @"
							+ ConversionUtil.convertToMHs(currentStatsRes.getData().getReportedHashrate()) + "MH/s.");
				}

				if (ConfigUtil.isStaleCheck()) {
					checkStale(currentStatsRes);
				}

			} else if (ConfigUtil.getMode() == ConfigUtil.CONFIG_MODE_INDIVIDUAL) {

				WorkersJsonResponse workersRes = (WorkersJsonResponse) HttpUtil.getJsonFromUrl(
						HttpUtil.getApiUrl(walletAddr, HttpUtil.API_FUNCTION_WORKERS), WorkersJsonResponse.class);

				if (workersRes == null || !HttpUtil.STATUS_OK.equals(workersRes.getStatus())) {
					throw new Exception("API is not working.");
				}

				List<Worker> workers = workersRes.getData();
				if (workers == null || workers.isEmpty()) {
					throw new IllegalArgumentException("No active workers");
				}

				Map<String, Double> problemWorkers = new LinkedHashMap<>();
				for (Worker worker : workers) {
					double expectedHash = ConfigUtil.getExpectedHash(worker.getWorker());
					if (expectedHash == 0) {
						throw new IllegalArgumentException(
								"Invalid expected hashrate. Please set workers 'expectedHash' property");
					}

					if (worker.getReportedHashrate() == null) {
						problemWorkers.put(worker.getWorker(), -1.0);
						continue;
					}

					if (worker.getReportedHashrate() < expectedHash) {
						problemWorkers.put(worker.getWorker(),
								ConversionUtil.convertToMHs(worker.getReportedHashrate()));
					}
				}

				if (! problemWorkers.isEmpty()) {
					manageReport(problemWorkers);
				} else if (lockfile.exists()) {
					lockfile.delete();
					MessagingUtil.sendMessage("Previously failed workers are back to normal.");
				}

			} else if (ConfigUtil.getMode() == ConfigUtil.CONFIG_MODE_MIX) {

				double totalExpectedHash = ConfigUtil.getExpectedHash(null);
				if (totalExpectedHash <= 0) {
					throw new IllegalArgumentException("Invalid expected hashrate. Please set 'expectedHash' property");
				}

				CurrentStatsJsonResponse currentStatsRes = (CurrentStatsJsonResponse) HttpUtil.getJsonFromUrl(
						HttpUtil.getApiUrl(walletAddr, HttpUtil.API_FUNCTION_CURRENTSTATS),
						CurrentStatsJsonResponse.class);

				if (currentStatsRes == null || !HttpUtil.STATUS_OK.equals(currentStatsRes.getStatus())) {
					throw new Exception("API is not working.");
				}

				if (currentStatsRes.getData().getReportedHashrate() < totalExpectedHash) {

					WorkersJsonResponse workersRes = (WorkersJsonResponse) HttpUtil.getJsonFromUrl(
							HttpUtil.getApiUrl(walletAddr, HttpUtil.API_FUNCTION_WORKERS), WorkersJsonResponse.class);

					if (workersRes == null || !HttpUtil.STATUS_OK.equals(workersRes.getStatus())) {
						throw new Exception("API is not working.");
					}

					List<Worker> workers = workersRes.getData();
					if (workers == null || workers.isEmpty()) {
						throw new IllegalArgumentException("No active workers");
					}

					Map<String, Double> problemWorkers = new LinkedHashMap<>();
					for (Worker worker : workers) {
						double expectedHash = ConfigUtil.getExpectedHash(worker.getWorker());
						if (expectedHash <= 0) {
							throw new IllegalArgumentException(
									"Invalid expected hashrate. Please set workers 'expectedHash' property");
						}

						if (worker.getReportedHashrate() == null) {
							problemWorkers.put(worker.getWorker(), -1.0);
							continue;
						}

						if (worker.getReportedHashrate() < expectedHash) {
							problemWorkers.put(worker.getWorker(),
									ConversionUtil.convertToMHs(worker.getReportedHashrate()));
						}
					}

					if (! problemWorkers.isEmpty()) {
						manageReport(problemWorkers);
					}
				} else if (lockfile.exists()) {
					lockfile.delete();
					MessagingUtil.sendMessage("Previously failed workers are back to normal.");
				}

				if (ConfigUtil.isStaleCheck()) {
					checkStale(currentStatsRes);
				}
			}

		} catch (Exception e) {
			e.printStackTrace();
			System.out.println(e.getMessage());
		} finally {
			runningfile.delete();
			try {
				runningfile.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
			System.out.println("Job ended.");
			System.exit(0);
		}
    }

    private static void checkStale(CurrentStatsJsonResponse currentStatsRes) throws Exception {
		if (currentStatsRes.getData().getStaleShares() > (currentStatsRes.getData().getValidShares() * ConfigUtil.getStaleTolerance() / 100)) {
			MessagingUtil.sendMessage("Stale shares are too high @ " +
					currentStatsRes.getData().getStaleShares() +  "/" +
					currentStatsRes.getData().getValidShares()
					+ ". Check your workers.%0A%0A<i>Stale Tolerance is set to " + ConfigUtil.getStaleTolerance() + "%</i>");
		}
	}

	private static void manageReport(Map<String, Double> problemWorkers) throws Exception {

		boolean reportFlag = true;

		if (lockfile.exists()) {
			lockfile.delete();
			reportFlag = ConfigUtil.isContinuousReport();
		}
		lockfile.createNewFile();

		if (reportFlag) {
			StringBuilder message = new StringBuilder();
			message.append("Reported hashrate is lower than expected for following worker(s).");
			for (String key : problemWorkers.keySet()) {
				if (problemWorkers.get(key) < 0) {
					message.append("%0A" + key + " is offline");
				} else {
					message.append("%0A" + key + " @" + problemWorkers.get(key) + "MH/s");
				}
			}

			MessagingUtil.sendMessage(message.toString());
		}
	}

}
