package pondsdataupdate;

import java.io.FileInputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.SelectOption;

public class Bgardhdiv {

	public static void main(String[] args) throws Exception {

		String spreadsheetId = "1PcC8R5l6TDFprqflZDDubufh5bUKenWOO6UbiQDSfFM";
		String range = "Sheet1!A2:D1000";

		GoogleCredential credential = GoogleCredential
				.fromStream(new FileInputStream("pondsdata-9b4461d3270d.json"))
				.createScoped(Collections.singleton("https://www.googleapis.com/auth/spreadsheets"));

		Sheets service = new Sheets.Builder(
				GoogleNetHttpTransport.newTrustedTransport(),
				JacksonFactory.getDefaultInstance(),
				credential
				).setApplicationName("Pond Data").build();

		ValueRange response = service.spreadsheets().values()
				.get(spreadsheetId, range)
				.execute();

		List<List<Object>> sheetRows = response.getValues();

		if (sheetRows == null || sheetRows.isEmpty()) {
			System.out.println("❌ Sheet mein koi data nahi mila!");
			return;
		}

		Map<String, String[]> sheetData = new LinkedHashMap<>();
		int duplicateCount = 0;

		for (List<Object> row : sheetRows) {
			String pondName   = row.size() >= 2 ? row.get(1).toString().trim() : "";
			String waterLevel = row.size() >= 3 ? row.get(2).toString().trim() : "";
			String remarks    = row.size() >= 4 ? row.get(3).toString().trim() : "";

			if (!pondName.isEmpty()) {
				// ✅ FIX: Duplicate naam hai toh unique key banao
				String uniqueKey = pondName;
				int dupCounter = 1;
				while (sheetData.containsKey(uniqueKey)) {
					uniqueKey = pondName + "_" + dupCounter;
					dupCounter++;
					duplicateCount++;
				}
				sheetData.put(uniqueKey, new String[]{waterLevel, remarks});
			}
		}

		System.out.println("✅ Sheet se " + sheetData.size() + " records padhe");
		System.out.println("⚠️ Duplicates found: " + duplicateCount);
		System.out.println("==========================================");

		try (Playwright playwright = Playwright.create()) {

			Browser browser = playwright.chromium().launch(
					new BrowserType.LaunchOptions().setHeadless(true)
					);

			Page page = browser.newPage();

			page.navigate("https://systems.hid.gov.in/MIS/");
			page.waitForTimeout(3000);

			try {
				page.frameLocator("iframe").locator("#txtuser").fill("9416495817");
				page.frameLocator("iframe").locator("#txtpass").fill("123456");
				page.frameLocator("iframe").locator("#btnLogin").click();
			} catch (Exception e) {
				page.fill("#txtuser", "9416495817");
				page.fill("#txtpass", "123456");
				page.click("#btnLogin");
			}

			page.waitForTimeout(5000);
			page.keyboard().press("Escape");
			System.out.println("✅ Login Successful");

			page.locator("img[src='/MIS/Content/assets/images/WaterMonitoringNew.jpg']").click();
			page.waitForTimeout(2000);

			page.navigate("https://systems.hid.gov.in/twp/UI/AddPondStatusDaily.aspx");
			page.waitForTimeout(5000);

			page.selectOption("#ContentPlaceHolder1_ddlUnit", "LCU");
			page.selectOption("#ContentPlaceHolder1_ddlCircle",
					new SelectOption().setLabel("Yamuna Water Service Circle, Rohtak"));
			page.selectOption("#ContentPlaceHolder1_ddldivision", "D0050");

			page.waitForSelector("#ContentPlaceHolder1_gvList");
			System.out.println("✅ Table Loaded");

			int updatedCount  = 0;
			int skippedCount  = 0;
			int notFoundCount = 0;

			for (Map.Entry<String, String[]> entry : sheetData.entrySet()) {

				// ✅ Duplicate key se original naam nikalo (_1, _2 remove karo)
				String uniqueKey     = entry.getKey();
				String sheetPondName = uniqueKey.replaceAll("_\\d+$", "").trim();
				String waterLevel    = entry.getValue()[0];
				String remarks       = entry.getValue()[1];

				System.out.println("\n🔍 Dhundh raha hoon: " + sheetPondName);

				try {
					page.waitForSelector("#ContentPlaceHolder1_gvList");
					Locator rows = page.locator("#ContentPlaceHolder1_gvList tr");
					int count    = rows.count();

					int matchedRowIndex    = -1;
					String matchedSiteName = null;

					// PASS 1: EXACT MATCH
					for (int i = 1; i < count; i++) {
						try {
							String fullText     = rows.nth(i).locator("td").nth(1).innerText().trim();
							String sitePondName = "";

							for (String line : fullText.split("\n")) {
								line = line.trim();
								if (line.startsWith("Pond Name :")) {
									sitePondName = line.replace("Pond Name :", "").trim();
									break;
								}
							}

							if (sitePondName.equalsIgnoreCase(sheetPondName)) {
								// ✅ Exact match mila, status check karo
								int candidateIndex     = i - 1;
								String candidateStatus = "";
								try {
									String cId = "#ContentPlaceHolder1_gvList_lblFilledStatus_" + candidateIndex;
									candidateStatus = page.locator(cId).innerText().trim();
								} catch (Exception ignored) {}

								if (!candidateStatus.isEmpty()) {
									// Already updated exact match — duplicate case
									System.out.println("   ⚠️ Exact match [" + sitePondName + "] already updated (" + candidateStatus + "), aage dhundh raha hoon...");
									continue;
								}

								matchedRowIndex = candidateIndex;
								matchedSiteName = sitePondName;
								System.out.println("   🎯 Exact Match!");
								break;
							}

						} catch (Exception ignored) {}
					}

					// PASS 2: PARTIAL MATCH (sirf empty status wali row)
					if (matchedRowIndex == -1) {
						for (int i = 1; i < count; i++) {
							try {
								String fullText     = rows.nth(i).locator("td").nth(1).innerText().trim();
								String sitePondName = "";

								for (String line : fullText.split("\n")) {
									line = line.trim();
									if (line.startsWith("Pond Name :")) {
										sitePondName = line.replace("Pond Name :", "").trim();
										break;
									}
								}

								if (sitePondName.isEmpty()) continue;

								boolean partialMatch =
										sitePondName.toLowerCase().contains(sheetPondName.toLowerCase()) ||
										sheetPondName.toLowerCase().contains(sitePondName.toLowerCase());

								if (partialMatch) {
									int candidateIndex     = i - 1;
									String candidateStatus = "";

									try {
										String cId = "#ContentPlaceHolder1_gvList_lblFilledStatus_" + candidateIndex;
										candidateStatus = page.locator(cId).innerText().trim();
									} catch (Exception ignored) {}

									if (!candidateStatus.isEmpty()) {
										System.out.println("   ⚠️ [" + sitePondName + "] already updated (" + candidateStatus + "), aage dhundh raha hoon...");
										continue;
									}

									matchedRowIndex = candidateIndex;
									matchedSiteName = sitePondName;
									System.out.println("   🔍 Partial Match (empty status)!");
									break;
								}

							} catch (Exception ignored) {}
						}
					}

					if (matchedRowIndex == -1) {
						System.out.println("   ⚠️ Match nahi mila site pe!");
						notFoundCount++;
						continue;
					}

					System.out.println("   ✅ Match: " + matchedSiteName + " | Index: " + matchedRowIndex);

					// STATUS DOUBLE CHECK
					String statusLabelId = "#ContentPlaceHolder1_gvList_lblFilledStatus_" + matchedRowIndex;
					String currentStatus = "";

					try {
						currentStatus = page.locator(statusLabelId).innerText().trim();
					} catch (Exception ignored) {}

					System.out.println("   📊 Current Status: [" + currentStatus + "]");

					if (!currentStatus.isEmpty()) {
						System.out.println("   ⏭️ Already updated! Skip.");
						skippedCount++;
						continue;
					}

					// EDIT CLICK
					String editBtnId = "#ContentPlaceHolder1_gvList_btnEdit_" + matchedRowIndex;

					if (page.locator(editBtnId).count() == 0) {
						System.out.println("   ⚠️ Edit button nahi mila! Skip.");
						skippedCount++;
						continue;
					}

					page.click(editBtnId);
					System.out.println("   🖱️ Edit Clicked...");

					// DROPDOWN WAIT
					String dropdownId  = "#ContentPlaceHolder1_gvList_ddlStatus_"  + matchedRowIndex;
					String remarksId   = "#ContentPlaceHolder1_gvList_txtReason_"  + matchedRowIndex;
					String updateBtnId = "#ContentPlaceHolder1_gvList_btn_Update_" + matchedRowIndex;

					page.waitForSelector(dropdownId, new Page.WaitForSelectorOptions()
							.setTimeout(10000));
					System.out.println("   ✅ Dropdown visible!");

					// WATER LEVEL SELECT
					if (!waterLevel.isEmpty()) {
						page.selectOption(dropdownId, new SelectOption().setLabel(waterLevel));
						page.waitForTimeout(500);
						System.out.println("   💧 Water Level: " + waterLevel);
					}

					// REMARKS FILL
					if (!remarks.isEmpty()) {
						page.fill(remarksId, "");
						page.fill(remarksId, remarks);
						page.waitForTimeout(500);
						System.out.println("   📝 Remarks: " + remarks);
					}

					// UPDATE CLICK
					page.click(updateBtnId);
					System.out.println("   🖱️ Update Clicked...");

					page.waitForTimeout(3000);
					page.waitForSelector("#ContentPlaceHolder1_gvList");

					System.out.println("   💾 Updated Successfully!");
					updatedCount++;

				} catch (Exception e) {
					System.out.println("   ❌ Error: " + e.getMessage());
				}
			}

			System.out.println("\n==========================================");
			System.out.println("✅ Updated  : " + updatedCount);
			System.out.println("⏭️ Skipped  : " + skippedCount);
			System.out.println("⚠️ Not Found: " + notFoundCount);
			System.out.println("==========================================");
		}
	}


}
