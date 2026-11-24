package com.bbf.pos;

import android.Manifest;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.util.Base64;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.bbf.pos.USB_PERMISSION";
    private static final String DEFAULT_UPI_ID = "Vyapar.173291080735@hdfcbank";
    private static final String DEFAULT_PAYEE_NAME = "BIG BOY'S FASHION";
    private static final String SHOP_PHONE_IGNORE = "7380792557";
    private static final int LINE_WIDTH = 48;

    private WebView webView;
    private String pendingPrintText = null;
    private byte[] pendingPrintBytes = null;
    private boolean usbReceiverRegistered = false;
    private final Object usbPrintLock = new Object();
    private final ExecutorService usbExecutor = Executors.newSingleThreadExecutor();
    private volatile String lastReceiptTextCache = "";
    private volatile String lastCustomerPhoneCache = "";
    private volatile long lastFullPrintTimestampMs = 0L;

    private void showStatusToast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (granted) {
                        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
                        UsbDevice targetDev = (device != null) ? device : findPrinterUsbDevice(um);
                        byte[] dataToSend = null;
                        String textToSend = null;
                        synchronized (usbPrintLock) {
                            if (pendingPrintBytes != null) {
                                dataToSend = pendingPrintBytes;
                                pendingPrintBytes = null;
                                pendingPrintText = null;
                            } else if (pendingPrintText != null) {
                                textToSend = pendingPrintText;
                                pendingPrintText = null;
                            }
                        }
                        if (targetDev != null) {
                            if (dataToSend != null) {
                                enqueueBytesToUsbDevice(targetDev, dataToSend, true);
                            } else if (textToSend != null) {
                                enqueueRawToUsbDevice(targetDev, textToSend);
                            }
                        }
                    } else {
                        synchronized (usbPrintLock) {
                            pendingPrintBytes = null;
                            pendingPrintText = null;
                        }
                        showStatusToast("USB permission allow karein print ke liye");
                    }
                }
            } catch (Exception e) {
                synchronized (usbPrintLock) {
                    pendingPrintBytes = null;
                    pendingPrintText = null;
                }
                showStatusToast("USB receiver error: " + e.getMessage());
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        registerUsbPermissionReceiver();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleExternalScheme(request != null ? request.getUrl() : null);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleExternalScheme(url != null ? Uri.parse(url) : null);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(Uri.parse(url));
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                try {
                    request.grant(request.getResources());
                } catch (Exception ignored) {}
            }
        });

        webView.addJavascriptInterface(new AndroidPOSBridge(), "AndroidPOS");

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                try {
                    if (url != null && url.startsWith("data:")) {
                        saveDataUrlToDownloads(url, mimeType);
                        return;
                    }
                    DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                    req.setMimeType(mimeType);
                    req.addRequestHeader("User-Agent", userAgent);
                    req.setDescription("Downloading BBF POS file...");
                    String ext = (mimeType != null && mimeType.contains("csv")) ? ".csv" : ".txt";
                    String fileName = "BBF_POS_" + System.currentTimeMillis() + ext;
                    req.setTitle(fileName);
                    req.allowScanningByMediaScanner();
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        dm.enqueue(req);
                        Toast.makeText(MainActivity.this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    private void captureReceiptTextFromWebViewThen(final java.util.function.Consumer<String> onTextReady) {
        if (webView == null) {
            onTextReady.accept(lastReceiptTextCache);
            return;
        }
        String js = "(function() {"
                + "var el = document.querySelector('#printArea, #receiptPreview, #billPreview, .receipt-paper');"
                + "if (el && el.innerText && el.innerText.trim().length > 20) return el.innerText;"
                + "var bodyText = document.body ? document.body.innerText : '';"
                + "var idx = bodyText.indexOf('BBF - BIG BOY');"
                + "if (idx >= 0) return bodyText.substring(idx);"
                + "return bodyText;"
                + "})();";
        webView.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String extracted = "";
                if (value != null && !value.equals("null")) {
                    extracted = value;
                    if (extracted.startsWith("\"") && extracted.endsWith("\"") && extracted.length() >= 2) {
                        extracted = extracted.substring(1, extracted.length() - 1)
                                .replace("\\n", "\n")
                                .replace("\\r", "")
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\");
                    }
                }
                if (extracted != null && extracted.trim().length() > 15) {
                    lastReceiptTextCache = extracted;
                    String foundCustPhone = extractCustomerPhoneOnly(extracted, null);
                    if (!foundCustPhone.isEmpty()) {
                        lastCustomerPhoneCache = foundCustPhone;
                    }
                    onTextReady.accept(extracted);
                } else {
                    onTextReady.accept(lastReceiptTextCache);
                }
            }
        });
    }

    private String normalizePhoneForWhatsApp(String rawPhone) {
        if (rawPhone == null) return "";
        String digits = rawPhone.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return "";
        if (digits.length() == 10) {
            return "91" + digits;
        }
        if (digits.startsWith("0") && digits.length() == 11) {
            return "91" + digits.substring(1);
        }
        return digits;
    }

    private boolean isShopNumber(String phoneDigits) {
        if (phoneDigits == null || phoneDigits.isEmpty()) return false;
        String clean = phoneDigits.replaceAll("[^0-9]", "");
        return clean.equals(SHOP_PHONE_IGNORE) || clean.equals("91" + SHOP_PHONE_IGNORE);
    }

    private String extractCustomerPhoneOnly(String text, Uri uri) {
        if (text != null && !text.trim().isEmpty()) {
            Matcher mCustLine = Pattern.compile("(?im)^\\s*(?:Cust|Customer|Phone|Mobile|Mob|WhatsApp|Contact)\\s*[:#\\-]?\\s*(.+)$").matcher(text);
            while (mCustLine.find()) {
                String lineVal = mCustLine.group(1);
                Matcher mNum = Pattern.compile("(\\+?91[\\s\\-]?[6-9][0-9]{9}|0?[6-9][0-9]{9}|[6-9][0-9]{9})").matcher(lineVal);
                while (mNum.find()) {
                    String norm = normalizePhoneForWhatsApp(mNum.group(1));
                    if (!norm.isEmpty() && !isShopNumber(norm)) {
                        return norm;
                    }
                }
            }

            Matcher mAnyMob = Pattern.compile("(?:\\+?91[\\s\\-]?)?([6-9][0-9]{9})").matcher(text);
            while (mAnyMob.find()) {
                String norm = normalizePhoneForWhatsApp(mAnyMob.group(1));
                if (!norm.isEmpty() && !isShopNumber(norm)) {
                    return norm;
                }
            }
        }

        if (uri != null) {
            String phoneParam = uri.getQueryParameter("phone");
            String normParam = normalizePhoneForWhatsApp(phoneParam);
            if (!normParam.isEmpty() && !isShopNumber(normParam)) {
                return normParam;
            }
            String raw = uri.toString();
            Matcher mWaMe = Pattern.compile("wa\\.me/([0-9]{10,15})").matcher(raw);
            if (mWaMe.find()) {
                String normWa = normalizePhoneForWhatsApp(mWaMe.group(1));
                if (!normWa.isEmpty() && !isShopNumber(normWa)) {
                    return normWa;
                }
            }
        }

        return "";
    }

    private boolean handleExternalScheme(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        String lowerScheme = scheme.toLowerCase(Locale.US);

        if ("http".equals(lowerScheme) || "https".equals(lowerScheme)) {
            String host = uri.getHost();
            if ("appassets.androidplatform.net".equals(host)) {
                return false;
            }
            if ("wa.me".equals(host) || (host != null && host.endsWith("whatsapp.com"))) {
                String txt = uri.getQueryParameter("text");
                String ph = extractCustomerPhoneOnly(txt, uri);
                if (ph.isEmpty()) ph = lastCustomerPhoneCache;
                return openWhatsAppShare(txt, ph);
            }
            try {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(browserIntent);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        if ("whatsapp".equals(lowerScheme) || "intent".equals(lowerScheme)) {
            String textParam = uri.getQueryParameter("text");
            String raw = uri.toString();
            if (textParam == null) {
                Matcher m = Pattern.compile("[?&]text=([^&]+)").matcher(raw);
                if (m.find()) {
                    textParam = Uri.decode(m.group(1));
                }
            }
            String ph = extractCustomerPhoneOnly(textParam, uri);
            if (ph.isEmpty()) ph = lastCustomerPhoneCache;
            return openWhatsAppShare(textParam, ph);
        }

        if ("upi".equals(lowerScheme) || "tel".equals(lowerScheme) || "mailto".equals(lowerScheme)) {
            try {
                Intent appIntent = new Intent(Intent.ACTION_VIEW, uri);
                appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(appIntent);
                return true;
            } catch (Exception e) {
                Toast.makeText(this, "Supported app nahi mila", Toast.LENGTH_SHORT).show();
                return true;
            }
        }

        return false;
    }

    private Bitmap generateSimpleQrBitmap(String content, int sizePx) {
        try {
            if (content == null || content.isEmpty()) return null;
            int cells = 25;
            boolean[][] matrix = new boolean[cells][cells];
            int hash = 0x811c9dc5;
            for (int i = 0; i < content.length(); i++) {
                hash ^= content.charAt(i);
                hash *= 0x01000193;
            }

            int[][] corners = new int[][]{{0, 0}, {0, cells - 7}, {cells - 7, 0}};
            for (int[] c : corners) {
                for (int r = 0; r < 7; r++) {
                    for (int col = 0; col < 7; col++) {
                        boolean border = (r == 0 || r == 6 || col == 0 || col == 6);
                        boolean center = (r >= 2 && r <= 4 && col >= 2 && col <= 4);
                        matrix[c[0] + r][c[1] + col] = (border || center);
                    }
                }
            }

            int bitIdx = 0;
            for (int r = 0; r < cells; r++) {
                for (int col = 0; col < cells; col++) {
                    boolean inTL = (r < 8 && col < 8);
                    boolean inTR = (r < 8 && col >= cells - 8);
                    boolean inBL = (r >= cells - 8 && col < 8);
                    if (inTL || inTR || inBL) continue;
                    int mix = hash ^ (r * 31 + col * 17 + bitIdx * 7);
                    matrix[r][col] = ((mix >>> (bitIdx % 16)) & 1) == 1;
                    bitIdx++;
                }
            }

            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(Color.WHITE);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.BLACK);
            float cellSize = (float) sizePx / cells;
            for (int r = 0; r < cells; r++) {
                for (int col = 0; col < cells; col++) {
                    if (matrix[r][col]) {
                        canvas.drawRect(col * cellSize, r * cellSize, (col + 1) * cellSize, (r + 1) * cellSize, p);
                    }
                }
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private File generateBillPdfFile(String text) {
        try {
            File pdfDir = new File(getCacheDir(), "pdfs");
            if (!pdfDir.exists()) pdfDir.mkdirs();

            String sourceText = (text != null && !text.trim().isEmpty()) ? text : lastReceiptTextCache;
            String billNo = extractBillNumber(sourceText);
            String dateStr = extractField(sourceText, "Date");
            String staffStr = extractField(sourceText, "Staff");
            String custStr = extractField(sourceText, "Cust");
            String modeStr = extractMode(sourceText);
            if (modeStr.isEmpty() && sourceText != null && sourceText.toUpperCase(Locale.US).contains("UPI")) {
                modeStr = "UPI";
            }

            String subtotalRaw = extractNumber(sourceText, "Subtotal");
            String discountRaw = extractNumber(sourceText, "Discount|Disc");
            String gstRaw = extractNumber(sourceText, "GST");
            String grandTotalRaw = extractNumber(sourceText, "GRAND\\s*TOTAL|PAY\\s*EXACT|NET\\s*AMOUNT|TOTAL");
            String loyalty = extractField(sourceText, "Loyalty\\s*Pts\\s*Earned|Loyalty");
            List<String[]> items = parseItems(sourceText);

            double itemsSum = 0.0;
            for (String[] it : items) {
                itemsSum += parseDoubleSafe(it[2]);
            }
            double subtotalVal = !subtotalRaw.isEmpty() ? parseDoubleSafe(subtotalRaw) : itemsSum;
            double discountVal = parseDoubleSafe(discountRaw);
            double gstVal = parseDoubleSafe(gstRaw);
            double grandTotalVal = Math.max(0.0, subtotalVal - discountVal + gstVal);
            if (!grandTotalRaw.isEmpty() && discountVal == 0.0) {
                double parsedGt = parseDoubleSafe(grandTotalRaw);
                if (parsedGt > 0.0 || subtotalVal == 0.0) {
                    grandTotalVal = parsedGt;
                }
            }

            String safeBillTag = !billNo.isEmpty() ? billNo.replaceAll("[^A-Za-z0-9\\-_]", "_") : String.valueOf(System.currentTimeMillis());
            File outFile = new File(pdfDir, "BBF_Bill_" + safeBillTag + ".pdf");

            int pageWidth = 360;
            int contentLeft = 20;
            int contentRight = pageWidth - 20;
            int estimatedHeight = 520 + (items.size() * 34);

            PdfDocument document = new PdfDocument();
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(pageWidth, estimatedHeight, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            canvas.drawColor(Color.WHITE);

            Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            titlePaint.setColor(Color.BLACK);
            titlePaint.setTextSize(16f);
            titlePaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            titlePaint.setTextAlign(Paint.Align.CENTER);

            Paint centerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            centerPaint.setColor(Color.BLACK);
            centerPaint.setTextSize(10.5f);
            centerPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            centerPaint.setTextAlign(Paint.Align.CENTER);

            Paint leftPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            leftPaint.setColor(Color.BLACK);
            leftPaint.setTextSize(10.5f);
            leftPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            leftPaint.setTextAlign(Paint.Align.LEFT);

            Paint boldLeftPaint = new Paint(leftPaint);
            boldLeftPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            boldLeftPaint.setTextSize(12f);

            Paint rightPaint = new Paint(leftPaint);
            rightPaint.setTextAlign(Paint.Align.RIGHT);

            Paint boldRightPaint = new Paint(rightPaint);
            boldRightPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            boldRightPaint.setTextSize(12f);

            Paint dashPaint = new Paint(leftPaint);
            dashPaint.setTextSize(10f);

            float y = 28f;
            canvas.drawText("BBF - BIG BOY'S FASHION", pageWidth / 2f, y, titlePaint);
            y += 16f;
            canvas.drawText("Chhota Baghada, Prayagraj, UP 211002", pageWidth / 2f, y, centerPaint);
            y += 14f;
            canvas.drawText("Mob: 7380792557", pageWidth / 2f, y, centerPaint);
            y += 14f;
            canvas.drawText("GSTIN: 09AABCB1234F1Z5", pageWidth / 2f, y, centerPaint);
            y += 14f;
            canvas.drawText("TAX INVOICE", pageWidth / 2f, y, centerPaint);

            y += 14f;
            String dashLine = "----------------------------------------";
            canvas.drawText(dashLine, contentLeft, y, dashPaint);
            y += 16f;

            if (!billNo.isEmpty()) {
                canvas.drawText("Bill: " + billNo, contentLeft, y, leftPaint);
            }
            if (!modeStr.isEmpty()) {
                canvas.drawText("Mode: " + modeStr, contentRight, y, rightPaint);
            }
            if (!billNo.isEmpty() || !modeStr.isEmpty()) y += 15f;

            if (!dateStr.isEmpty()) {
                canvas.drawText("Date: " + dateStr, contentLeft, y, leftPaint);
                y += 15f;
            }
            if (!staffStr.isEmpty()) {
                canvas.drawText("Staff: " + staffStr, contentLeft, y, leftPaint);
                y += 15f;
            }
            if (!custStr.isEmpty()) {
                canvas.drawText("Cust: " + custStr, contentLeft, y, leftPaint);
                y += 15f;
            }

            canvas.drawText(dashLine, contentLeft, y, dashPaint);
            y += 16f;

            for (String[] item : items) {
                canvas.drawText(item[0], contentLeft, y, leftPaint);
                y += 14f;
                if (!item[1].isEmpty()) {
                    canvas.drawText("  " + item[1], contentLeft, y, leftPaint);
                }
                canvas.drawText("Rs." + item[2], contentRight, y, rightPaint);
                y += 16f;
            }

            canvas.drawText(dashLine, contentLeft, y, dashPaint);
            y += 16f;

            if (!subtotalRaw.isEmpty() || discountVal > 0.0 || gstVal > 0.0) {
                canvas.drawText("Subtotal:", contentLeft, y, leftPaint);
                canvas.drawText("Rs." + String.format(Locale.US, "%.2f", subtotalVal), contentRight, y, rightPaint);
                y += 15f;
            }
            if (discountVal > 0.0) {
                canvas.drawText("Discount:", contentLeft, y, leftPaint);
                canvas.drawText("-Rs." + String.format(Locale.US, "%.2f", discountVal), contentRight, y, rightPaint);
                y += 15f;
            }
            if (gstVal > 0.0) {
                canvas.drawText("GST:", contentLeft, y, leftPaint);
                canvas.drawText("Rs." + String.format(Locale.US, "%.2f", gstVal), contentRight, y, rightPaint);
                y += 15f;
            }

            String fmtTotal = String.format(Locale.US, "%.2f", grandTotalVal);
            canvas.drawText("GRAND TOTAL:", contentLeft, y, boldLeftPaint);
            canvas.drawText("Rs." + fmtTotal, contentRight, y, boldRightPaint);
            y += 16f;

            if (!loyalty.isEmpty()) {
                String cleanLoyalty = loyalty.replaceAll("(?i)^Loyalty\\s*Pts\\s*Earned\\s*[:]?\\s*", "").trim();
                canvas.drawText("Loyalty Pts Earned:", contentLeft, y, leftPaint);
                canvas.drawText(cleanLoyalty, contentRight, y, rightPaint);
                y += 15f;
            }

            canvas.drawText(dashLine, contentLeft, y, dashPaint);
            y += 18f;

            String upiPayload = buildUpiPayload(fmtTotal, billNo);
            canvas.drawText("PAY EXACT: Rs." + fmtTotal, pageWidth / 2f, y, centerPaint);
            y += 8f;
            Bitmap qrBmp = generateSimpleQrBitmap(upiPayload, 110);
            if (qrBmp != null) {
                float qrLeft = (pageWidth - qrBmp.getWidth()) / 2f;
                canvas.drawBitmap(qrBmp, qrLeft, y, null);
                y += qrBmp.getHeight() + 14f;
            }

            canvas.drawText("Thank You! Visit Again.", pageWidth / 2f, y, centerPaint);
            y += 14f;
            canvas.drawText("Exchange within 7 days with bill and tag.", pageWidth / 2f, y, centerPaint);

            document.finishPage(page);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                document.writeTo(fos);
            }
            document.close();
            return outFile;
        } catch (Exception e) {
            return null;
        }
    }

    private void grantUriPermissionToPackages(Intent intent, Uri uri, String[] packages) {
        for (String pkg : packages) {
            try {
                grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
        try {
            List<ResolveInfo> resInfoList = getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo resolveInfo : resInfoList) {
                String packageName = resolveInfo.activityInfo.packageName;
                try {
                    grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private boolean openWhatsAppShare(String text, String phone) {
        String message = (text != null && !text.trim().isEmpty()) ? text : lastReceiptTextCache;
        if (message == null) message = "";

        String targetPhone = normalizePhoneForWhatsApp(phone);
        if (targetPhone.isEmpty() || isShopNumber(targetPhone)) {
            targetPhone = extractCustomerPhoneOnly(message, null);
        }
        if ((targetPhone.isEmpty() || isShopNumber(targetPhone)) && !lastCustomerPhoneCache.isEmpty() && !isShopNumber(lastCustomerPhoneCache)) {
            targetPhone = lastCustomerPhoneCache;
        }
        if (isShopNumber(targetPhone)) {
            targetPhone = "";
        }

        String[] packages = new String[]{"com.whatsapp", "com.whatsapp.w4b"};

        try {
            File pdfFile = generateBillPdfFile(message);
            if (pdfFile != null && pdfFile.exists()) {
                String authority = getPackageName() + ".fileprovider";
                Uri pdfUri = FileProvider.getUriForFile(this, authority, pdfFile);

                if (!targetPhone.isEmpty()) {
                    for (String pkg : packages) {
                        try {
                            Intent chatIntent = new Intent(Intent.ACTION_SEND);
                            chatIntent.setType("application/pdf");
                            chatIntent.putExtra(Intent.EXTRA_STREAM, pdfUri);
                            chatIntent.putExtra("jid", targetPhone + "@s.whatsapp.net");
                            chatIntent.putExtra("address", "+" + targetPhone);
                            chatIntent.putExtra(Intent.EXTRA_PHONE_NUMBER, "+" + targetPhone);
                            chatIntent.setPackage(pkg);
                            chatIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            grantUriPermission(pkg, pdfUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(chatIntent);
                            return true;
                        } catch (ActivityNotFoundException ignored) {}
                    }
                }

                Intent pdfIntent = new Intent(Intent.ACTION_SEND);
                pdfIntent.setType("application/pdf");
                pdfIntent.putExtra(Intent.EXTRA_STREAM, pdfUri);
                pdfIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                grantUriPermissionToPackages(pdfIntent, pdfUri, packages);

                for (String pkg : packages) {
                    try {
                        Intent specific = new Intent(pdfIntent);
                        specific.setPackage(pkg);
                        specific.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(specific);
                        return true;
                    } catch (ActivityNotFoundException ignored) {}
                }

                Intent chooser = Intent.createChooser(pdfIntent, "Share Bill PDF");
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(chooser);
                return true;
            }

            if (!targetPhone.isEmpty()) {
                Uri waUri = Uri.parse("https://api.whatsapp.com/send?phone=" + targetPhone + "&text=" + Uri.encode(message));
                Intent viewIntent = new Intent(Intent.ACTION_VIEW, waUri);
                viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(viewIntent);
                return true;
            }

            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("text/plain");
            sendIntent.putExtra(Intent.EXTRA_TEXT, message);
            Intent chooser = Intent.createChooser(sendIntent, "Share Bill");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "WhatsApp PDF share mein dikkat aayi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return true;
        }
    }

    private void registerUsbPermissionReceiver() {
        if (usbReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(usbPermissionReceiver, filter);
            }
            usbReceiverRegistered = true;
        } catch (Exception ignored) {}
    }

    private void saveDataUrlToDownloads(String dataUrl, String mimeType) {
        try {
            int commaIdx = dataUrl.indexOf(',');
            if (commaIdx == -1) return;
            String base64Part = dataUrl.substring(commaIdx + 1);
            byte[] decoded = Base64.decode(base64Part, Base64.DEFAULT);
            String ext = (mimeType != null && mimeType.contains("json")) ? ".json" : ".txt";
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dir.exists()) dir.mkdirs();
            File outFile = new File(dir, "BBF_POS_" + System.currentTimeMillis() + ext);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(decoded);
            }
            Toast.makeText(this, "Saved to Downloads: " + outFile.getName(), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private UsbDevice findPrinterUsbDevice(UsbManager usbManager) {
        if (usbManager == null) return null;
        HashMap<String, UsbDevice> list = usbManager.getDeviceList();
        if (list == null || list.isEmpty()) return null;
        for (UsbDevice dev : list.values()) {
            for (int i = 0; i < dev.getInterfaceCount(); i++) {
                UsbInterface iface = dev.getInterface(i);
                if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                    return dev;
                }
            }
        }
        return list.values().iterator().next();
    }

    private byte[] buildDrawerAndCutFooter() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(new byte[]{0x1B, 0x70, 0x00, 0x19, (byte) 0xFA});
            baos.write(new byte[]{0x1B, 0x70, 0x01, 0x19, (byte) 0xFA});
        } catch (IOException ignored) {}
        return baos.toByteArray();
    }

    private String extractField(String text, String labelRegex) {
        if (text == null) return "";
        Matcher m = Pattern.compile("(?im)^\\s*" + labelRegex + "\\s*[:#\\-]?\\s*(.+)$").matcher(text);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "";
    }

    private String extractBillNumber(String text) {
        String raw = extractField(text, "Bill");
        if (!raw.isEmpty()) {
            Matcher mCode = Pattern.compile("([A-Za-z]+-[0-9]+|[A-Za-z0-9]+)").matcher(raw);
            if (mCode.find()) return mCode.group(1).trim();
        }
        if (text != null) {
            Matcher m = Pattern.compile("(?i)Bill\\s*[:#\\-]?\\s*([A-Za-z0-9\\-]+)").matcher(text);
            if (m.find()) return m.group(1).trim();
        }
        return "";
    }

    private String extractMode(String text) {
        if (text == null) return "";
        Matcher inline = Pattern.compile("(?i)Mode\\s*[:\\-]\\s*([A-Za-z0-9 /]+)").matcher(text);
        if (inline.find()) {
            return inline.group(1).trim();
        }
        Matcher bulletMode = Pattern.compile("(?i)Bill\\s*:[^\\n]*[|\\u2022]\\s*([A-Za-z0-9 /]+)").matcher(text);
        if (bulletMode.find()) {
            return bulletMode.group(1).trim();
        }
        return "";
    }

    private String extractNumber(String text, String labelRegex) {
        if (text == null) return "";
        Matcher m = Pattern.compile("(?im)^\\s*(?:" + labelRegex + ")\\s*[:]?\\s*(?:-\\s*)?(?:Rs\\.?|INR|\\u20B9)?\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*$").matcher(text);
        String found = "";
        while (m.find()) {
            found = m.group(1).trim();
        }
        if (found.isEmpty()) {
            Matcher mAny = Pattern.compile("(?im)(?:" + labelRegex + ")\\s*[:]?\\s*(?:-\\s*)?(?:Rs\\.?|INR|\\u20B9)?\\s*([0-9]+(?:\\.[0-9]{1,2})?)").matcher(text);
            while (mAny.find()) {
                found = mAny.group(1).trim();
            }
        }
        return found;
    }

    private double parseDoubleSafe(String val) {
        if (val == null || val.trim().isEmpty()) return 0.0;
        try {
            return Double.parseDouble(val.trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String formatAmount(String val) {
        double d = parseDoubleSafe(val);
        return String.format(Locale.US, "%.2f", d);
    }

    private String leftRight(String left, String right) {
        String l = (left == null) ? "" : left.trim();
        String r = (right == null) ? "" : right.trim();
        int spaces = LINE_WIDTH - l.length() - r.length();
        if (spaces < 1) spaces = 1;
        StringBuilder sb = new StringBuilder();
        sb.append(l);
        for (int i = 0; i < spaces; i++) sb.append(' ');
        sb.append(r);
        return sb.toString();
    }

    private String repeatChar(char ch, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append(ch);
        return sb.toString();
    }

    private List<String[]> parseItems(String text) {
        List<String[]> items = new ArrayList<>();
        if (text == null) return items;
        String[] lines = text.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.matches("(?i).*(?:BBF\\s*-\\s*BIG\\s*BOY'?S\\s*FASHION|Chhota\\s*Baghada|Mob:|GSTIN:|TAX\\s*INVOICE|Bill\\s*:|Date:|Staff:|Cust:|Subtotal:|Discount:|Disc:|GST:|GRAND\\s*TOTAL|Loyalty|PAY\\s*EXACT|Thank\\s*You|Exchange\\s*within|upi://pay).*")) {
                continue;
            }
            Matcher mInline = Pattern.compile("^(.+?)\\s+(?:([0-9]+\\s*x\\s*(?:Rs\\.?|\\u20B9)?\\s*[0-9.]+)\\s*=\\s*)?(?:Rs\\.?|INR|\\u20B9)?\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*$").matcher(line);
            if (mInline.find()) {
                String name = mInline.group(1).trim();
                String qtyRate = (mInline.group(2) != null) ? mInline.group(2).trim() : "";
                String total = formatAmount(mInline.group(3));
                items.add(new String[]{name, qtyRate, total});
                continue;
            }
            if (i + 1 < lines.length) {
                String next = lines[i + 1].trim();
                Matcher mQtyAmt = Pattern.compile("^(?:([0-9]+\\s*x\\s*(?:Rs\\.?|\\u20B9)?\\s*[0-9.]+)\\s*(?:=\\s*)?)?(?:Rs\\.?|INR|\\u20B9)?\\s*([0-9]+(?:\\.[0-9]{1,2})?)\\s*$").matcher(next);
                if (mQtyAmt.find()) {
                    String qtyRate = (mQtyAmt.group(1) != null) ? mQtyAmt.group(1).trim() : "";
                    String total = formatAmount(mQtyAmt.group(2));
                    items.add(new String[]{line, qtyRate, total});
                    i++;
                }
            }
        }
        return items;
    }

    private String buildUpiPayload(String amount, String billNo) {
        if (amount == null || amount.trim().isEmpty()) return "";
        String cleanBill = (billNo != null) ? billNo.trim().replaceAll("[^A-Za-z0-9]", "") : "";
        String trRef = !cleanBill.isEmpty() ? cleanBill : ("BBF" + (System.currentTimeMillis() % 100000));
        return "upi://pay?pa=" + Uri.encode(DEFAULT_UPI_ID)
                + "&pn=" + Uri.encode(DEFAULT_PAYEE_NAME)
                + "&tr=" + Uri.encode(trRef)
                + "&am=" + Uri.encode(amount.trim())
                + "&cu=INR";
    }

    private boolean isConnectOnlyText(String text) {
        if (text == null) return true;
        String t = text.trim();
        if (t.isEmpty()) return true;
        String lower = t.toLowerCase(Locale.US);
        boolean hasBillData = lower.contains("bill") || lower.contains("subtotal") || lower.contains("grand total")
                || lower.contains("total") || lower.contains("amount") || lower.contains("rs.") || lower.contains("\u20b9")
                || lower.contains("qty") || lower.contains("gst") || lower.contains("cash") || lower.contains("upi");
        if (!hasBillData && (lower.contains("connect") || lower.contains("test") || lower.contains("ready") || t.length() < 45)) {
            return true;
        }
        return false;
    }

    private byte[] buildConnectTestEscPosBytes() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(new byte[]{0x1B, 0x40});
            baos.write(new byte[]{0x1B, 0x61, 0x01});
            baos.write(new byte[]{0x1B, 0x45, 0x01});
            baos.write(new byte[]{0x1D, 0x21, 0x11});
            baos.write("BBF - BIG BOY'S FASHION\n".getBytes(StandardCharsets.US_ASCII));
            baos.write(new byte[]{0x1D, 0x21, 0x00});
            baos.write(new byte[]{0x1B, 0x45, 0x00});
            baos.write("USB OTG Printer Connected\n".getBytes(StandardCharsets.US_ASCII));
            baos.write(new byte[]{0x1B, 0x64, 0x03});
            baos.write(new byte[]{0x1D, 0x56, 0x42, 0x00});
            baos.write(buildDrawerAndCutFooter());
        } catch (Exception ignored) {}
        return baos.toByteArray();
    }

    private String sanitizeForThermalPrinter(String s) {
        if (s == null) return "";
        return s.replace("\u20b9", "Rs.")
                .replace("\u2022", "|")
                .replace("\u00a0", " ")
                .replaceAll("[^\\x00-\\x7F]", " ");
    }

    private byte[] buildReceiptEscPosBytes(String text) {
        if (isConnectOnlyText(text)) {
            return buildConnectTestEscPosBytes();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            baos.write(new byte[]{0x1B, 0x40});

            String billNo = extractBillNumber(text);
            String dateStr = extractField(text, "Date");
            String staffStr = extractField(text, "Staff");
            String custStr = extractField(text, "Cust");
            String modeStr = extractMode(text);
            if (modeStr.isEmpty() && text != null && text.toUpperCase(Locale.US).contains("UPI")) {
                modeStr = "UPI";
            }

            String subtotalRaw = extractNumber(text, "Subtotal");
            String discountRaw = extractNumber(text, "Discount|Disc");
            String gstRaw = extractNumber(text, "GST");
            String grandTotalRaw = extractNumber(text, "GRAND\\s*TOTAL|PAY\\s*EXACT|NET\\s*AMOUNT|TOTAL");
            String loyalty = extractField(text, "Loyalty\\s*Pts\\s*Earned|Loyalty");
            List<String[]> items = parseItems(text);

            double itemsSum = 0.0;
            for (String[] it : items) {
                itemsSum += parseDoubleSafe(it[2]);
            }

            double subtotalVal = !subtotalRaw.isEmpty() ? parseDoubleSafe(subtotalRaw) : itemsSum;
            double discountVal = parseDoubleSafe(discountRaw);
            double gstVal = parseDoubleSafe(gstRaw);

            double grandTotalVal = Math.max(0.0, subtotalVal - discountVal + gstVal);
            if (!grandTotalRaw.isEmpty()) {
                double parsedGt = parseDoubleSafe(grandTotalRaw);
                if (parsedGt > 0.0 || subtotalVal == 0.0) {
                    grandTotalVal = parsedGt;
                }
            }

            baos.write(new byte[]{0x1B, 0x61, 0x01});
            baos.write(new byte[]{0x1B, 0x45, 0x01});
            baos.write(new byte[]{0x1D, 0x21, 0x11});
            baos.write(sanitizeForThermalPrinter("BBF - BIG BOY'S FASHION\n").getBytes(StandardCharsets.US_ASCII));
            baos.write(new byte[]{0x1D, 0x21, 0x00});
            baos.write(new byte[]{0x1B, 0x45, 0x00});

            baos.write(sanitizeForThermalPrinter("Chhota Baghada, Prayagraj, Uttar Pradesh 211002\n").getBytes(StandardCharsets.US_ASCII));
            baos.write(sanitizeForThermalPrinter("Mob: 7380792557\n").getBytes(StandardCharsets.US_ASCII));
            baos.write(sanitizeForThermalPrinter("GSTIN: 09AABCB1234F1Z5\n").getBytes(StandardCharsets.US_ASCII));
            baos.write(sanitizeForThermalPrinter("TAX INVOICE\n").getBytes(StandardCharsets.US_ASCII));

            baos.write(new byte[]{0x1B, 0x61, 0x00});
            baos.write((repeatChar('-', LINE_WIDTH) + "\n").getBytes(StandardCharsets.US_ASCII));
            if (!billNo.isEmpty() || !modeStr.isEmpty()) {
                String leftBill = !billNo.isEmpty() ? ("Bill: " + sanitizeForThermalPrinter(billNo)) : "";
                String rightMode = !modeStr.isEmpty() ? ("Mode: " + sanitizeForThermalPrinter(modeStr)) : "";
                baos.write((leftRight(leftBill, rightMode) + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            if (!dateStr.isEmpty()) {
                baos.write((sanitizeForThermalPrinter("Date: " + dateStr) + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            if (!staffStr.isEmpty()) {
                baos.write((sanitizeForThermalPrinter("Staff: " + staffStr) + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            if (!custStr.isEmpty()) {
                baos.write((sanitizeForThermalPrinter("Cust: " + custStr) + "\n").getBytes(StandardCharsets.US_ASCII));
            }

            baos.write((repeatChar('-', LINE_WIDTH) + "\n").getBytes(StandardCharsets.US_ASCII));
            for (String[] item : items) {
                String name = sanitizeForThermalPrinter(item[0]);
                String qtyRate = sanitizeForThermalPrinter(item[1]);
                String amt = sanitizeForThermalPrinter("Rs." + item[2]);
                baos.write((name + "\n").getBytes(StandardCharsets.US_ASCII));
                if (!qtyRate.isEmpty()) {
                    baos.write((leftRight("  " + qtyRate, amt) + "\n").getBytes(StandardCharsets.US_ASCII));
                } else {
                    baos.write((leftRight("", amt) + "\n").getBytes(StandardCharsets.US_ASCII));
                }
            }
            baos.write((repeatChar('-', LINE_WIDTH) + "\n").getBytes(StandardCharsets.US_ASCII));

            if (!subtotalRaw.isEmpty() || discountVal > 0.0 || gstVal > 0.0) {
                baos.write((leftRight("Subtotal:", "Rs." + String.format(Locale.US, "%.2f", subtotalVal)) + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            if (gstVal > 0.0) {
                baos.write((leftRight("GST:", "Rs." + String.format(Locale.US, "%.2f", gstVal)) + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            if (discountVal > 0.0) {
                baos.write((leftRight("Discount:", "-Rs." + String.format(Locale.US, "%.2f", discountVal)) + "\n").getBytes(StandardCharsets.US_ASCII));
            }

            baos.write(new byte[]{0x1B, 0x45, 0x01});
            baos.write(new byte[]{0x1D, 0x21, 0x01});
            baos.write((leftRight("GRAND TOTAL:", "Rs." + String.format(Locale.US, "%.2f", grandTotalVal)) + "\n").getBytes(StandardCharsets.US_ASCII));
            baos.write(new byte[]{0x1D, 0x21, 0x00});
            baos.write(new byte[]{0x1B, 0x45, 0x00});

            if (!loyalty.isEmpty()) {
                String cleanLoyalty = sanitizeForThermalPrinter(loyalty.replaceAll("(?i)^Loyalty\\s*Pts\\s*Earned\\s*[:]?\\s*", "")).trim();
                baos.write((leftRight("Loyalty Pts Earned:", cleanLoyalty) + "\n").getBytes(StandardCharsets.US_ASCII));
            }
            baos.write((repeatChar('-', LINE_WIDTH) + "\n").getBytes(StandardCharsets.US_ASCII));

            String fmtTotal = String.format(Locale.US, "%.2f", grandTotalVal);
            baos.write(new byte[]{0x1B, 0x61, 0x01});
            baos.write(new byte[]{0x1B, 0x45, 0x01});
            baos.write(("PAY EXACT: Rs." + fmtTotal + "\n").getBytes(StandardCharsets.US_ASCII));
            baos.write(new byte[]{0x1B, 0x45, 0x00});
            baos.write(("UPI ID: " + DEFAULT_UPI_ID + "\n").getBytes(StandardCharsets.US_ASCII));

            baos.write(new byte[]{0x1B, 0x61, 0x00});
            baos.write((repeatChar('-', LINE_WIDTH) + "\n").getBytes(StandardCharsets.US_ASCII));
            baos.write(new byte[]{0x1B, 0x61, 0x01});
            baos.write("Thank You! Visit Again.\n".getBytes(StandardCharsets.US_ASCII));
            baos.write("Exchange within 7 days with bill and tag.\n".getBytes(StandardCharsets.US_ASCII));
            baos.write(new byte[]{0x1B, 0x61, 0x00});

            baos.write(new byte[]{0x1B, 0x64, 0x04});
            baos.write(new byte[]{0x1D, 0x56, 0x42, 0x00});
            baos.write(buildDrawerAndCutFooter());
        } catch (Exception ignored) {}
        return baos.toByteArray();
    }

    private void enqueueRawToUsbDevice(UsbDevice device, String text) {
        String sourceText = (text != null && !text.trim().isEmpty()) ? text : lastReceiptTextCache;
        byte[] payload = buildReceiptEscPosBytes(sourceText);
        enqueueBytesToUsbDevice(device, payload, true);
    }

    private int writeChunked(UsbDeviceConnection connection, UsbEndpoint ep, byte[] bytes, int chunkSize) {
        int totalWritten = 0;
        int offset = 0;
        while (offset < bytes.length) {
            int len = Math.min(chunkSize, bytes.length - offset);
            int sent = connection.bulkTransfer(ep, bytes, offset, len, 4000);
            if (sent < 0) {
                return sent;
            }
            offset += sent;
            totalWritten += sent;
            try { Thread.sleep(5L); } catch (InterruptedException ignored) {}
        }
        return totalWritten;
    }

    private int writeBulkAll(UsbDeviceConnection connection, UsbEndpoint ep, byte[] bytes) {
        int res = writeChunked(connection, ep, bytes, 128);
        if (res > 0) return res;
        return writeChunked(connection, ep, bytes, 64);
    }

    private void enqueueBytesToUsbDevice(final UsbDevice device, final byte[] bytes, final boolean showToast) {
        if (device == null || bytes == null || bytes.length == 0) return;
        usbExecutor.execute(() -> {
            synchronized (usbPrintLock) {
                UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
                if (usbManager == null) {
                    showStatusToast("USB service available nahi hai");
                    return;
                }

                UsbDevice targetDevice = findPrinterUsbDevice(usbManager);
                if (targetDevice == null) {
                    showStatusToast("USB / OTG printer connected nahi hai");
                    return;
                }

                UsbDeviceConnection connection = null;
                List<UsbInterface> claimedList = new ArrayList<>();
                try {
                    connection = usbManager.openDevice(targetDevice);
                    if (connection == null) {
                        showStatusToast("USB printer open nahi ho paaya");
                        return;
                    }

                    List<UsbEndpoint> outEndpoints = new ArrayList<>();
                    for (int i = 0; i < targetDevice.getInterfaceCount(); i++) {
                        UsbInterface iface = targetDevice.getInterface(i);
                        boolean ifaceHasOut = false;
                        for (int e = 0; e < iface.getEndpointCount(); e++) {
                            UsbEndpoint ep = iface.getEndpoint(e);
                            if (ep.getDirection() == UsbConstants.USB_DIR_OUT &&
                                    (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK || ep.getType() == UsbConstants.USB_ENDPOINT_XFER_INT)) {
                                outEndpoints.add(ep);
                                ifaceHasOut = true;
                            }
                        }
                        if (ifaceHasOut && (connection.claimInterface(iface, true) || connection.claimInterface(iface, false))) {
                            claimedList.add(iface);
                        }
                    }

                    if (outEndpoints.isEmpty()) {
                        showStatusToast("USB printer OUT endpoint nahi mila");
                        return;
                    }

                    int written = -1;
                    for (UsbEndpoint ep : outEndpoints) {
                        written = writeBulkAll(connection, ep, bytes);
                        if (written > 0) {
                            break;
                        }
                    }

                    final int finalWritten = written;
                    final int epCount = outEndpoints.size();
                    final int clCount = claimedList.size();
                    if (showToast && bytes.length > 20) {
                        showStatusToast(finalWritten > 0
                                ? ("Printed via USB OTG (" + finalWritten + "B)")
                                : ("USB transfer failed (" + finalWritten + "B, ep=" + epCount + ", cl=" + clCount + ")"));
                    }
                } catch (Exception e) {
                    showStatusToast("USB print error: " + e.getMessage());
                } finally {
                    for (UsbInterface iface : claimedList) {
                        if (connection != null) {
                            try { connection.releaseInterface(iface); } catch (Exception ignored) {}
                        }
                    }
                    if (connection != null) {
                        try { connection.close(); } catch (Exception ignored) {}
                    }
                }
            }
        });
    }

    private void triggerUsbPrint(String text, byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length > 20) {
            lastFullPrintTimestampMs = System.currentTimeMillis();
        }
        try {
            UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
            UsbDevice device = findPrinterUsbDevice(usbManager);
            if (device == null) {
                showStatusToast("USB / OTG printer connected nahi hai");
                return;
            }
            boolean hasPerm = usbManager.hasPermission(device);
            if (hasPerm) {
                if (rawBytes != null) enqueueBytesToUsbDevice(device, rawBytes, rawBytes.length > 20);
                else enqueueRawToUsbDevice(device, text);
            } else {
                showStatusToast("Requesting USB permission...");
                synchronized (usbPrintLock) {
                    pendingPrintText = text;
                    pendingPrintBytes = rawBytes;
                }
                Intent usbIntent = new Intent(ACTION_USB_PERMISSION);
                usbIntent.setPackage(getPackageName());
                int flags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ? PendingIntent.FLAG_MUTABLE : 0;
                int reqCode = (int) (System.currentTimeMillis() % 100000);
                PendingIntent pi = PendingIntent.getBroadcast(this, reqCode, usbIntent, flags);
                usbManager.requestPermission(device, pi);
            }
        } catch (Exception e) {
            showStatusToast("USB trigger error: " + e.getMessage());
        }
    }

    public class AndroidPOSBridge {
        @JavascriptInterface
        public void printUsbRaw(final String text) {
            runOnUiThread(() -> {
                try {
                    showStatusToast("Sending bill to USB OTG...");
                    if (text != null && !text.trim().isEmpty()) {
                        lastReceiptTextCache = text;
                        String cp = extractCustomerPhoneOnly(text, null);
                        if (!cp.isEmpty()) lastCustomerPhoneCache = cp;
                        triggerUsbPrint(text, null);
                    } else {
                        captureReceiptTextFromWebViewThen(extracted -> {
                            String toPrint = (extracted != null && !extracted.trim().isEmpty()) ? extracted : lastReceiptTextCache;
                            triggerUsbPrint(toPrint != null ? toPrint : "", null);
                        });
                    }
                } catch (Exception e) {
                    showStatusToast("Print error: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void printEscPosBase64(final String base64Data) {
            runOnUiThread(() -> {
                try {
                    byte[] decoded = Base64.decode(base64Data, Base64.DEFAULT);
                    if (decoded == null || decoded.length == 0) return;
                    if (decoded.length <= 20) {
                        UsbManager um = (UsbManager) getSystemService(Context.USB_SERVICE);
                        UsbDevice dev = findPrinterUsbDevice(um);
                        if (dev != null && um != null && um.hasPermission(dev)) {
                            enqueueBytesToUsbDevice(dev, decoded, false);
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastFullPrintTimestampMs > 1500L) {
                            if (lastReceiptTextCache != null && !lastReceiptTextCache.trim().isEmpty()) {
                                triggerUsbPrint(lastReceiptTextCache, null);
                            } else {
                                captureReceiptTextFromWebViewThen(extracted -> triggerUsbPrint(extracted, null));
                            }
                        }
                        return;
                    }
                    triggerUsbPrint(null, decoded);
                } catch (Exception e) {
                    showStatusToast("Base64 print error");
                }
            });
        }

        @JavascriptInterface
        public void shareWhatsApp(final String text) {
            runOnUiThread(() -> {
                if (text != null && !text.trim().isEmpty()) {
                    lastReceiptTextCache = text;
                    String cp = extractCustomerPhoneOnly(text, null);
                    if (!cp.isEmpty()) lastCustomerPhoneCache = cp;
                    openWhatsAppShare(text, cp);
                } else {
                    captureReceiptTextFromWebViewThen(extracted -> openWhatsAppShare(extracted, lastCustomerPhoneCache));
                }
            });
        }

        @JavascriptInterface
        public void shareWhatsAppToNumber(final String text, final String phone) {
            runOnUiThread(() -> {
                String cleanPhone = normalizePhoneForWhatsApp(phone);
                if (!cleanPhone.isEmpty() && !isShopNumber(cleanPhone)) {
                    lastCustomerPhoneCache = cleanPhone;
                }
                if (text != null && !text.trim().isEmpty()) {
                    lastReceiptTextCache = text;
                    openWhatsAppShare(text, cleanPhone);
                } else {
                    captureReceiptTextFromWebViewThen(extracted -> openWhatsAppShare(extracted, cleanPhone));
                }
            });
        }

        @JavascriptInterface
        public void printPage() {
            runOnUiThread(() -> {
                try {
                    PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                    if (printManager != null && webView != null) {
                        PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter("BBF_POS_Receipt");
                        printManager.print("BBF_POS_Receipt", adapter, new PrintAttributes.Builder().build());
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Print error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void requestPermissions(String requested) {
            runOnUiThread(() -> {
                try {
                    List<String> needed = new ArrayList<>();
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        needed.add(Manifest.permission.CAMERA);
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
                        }
                        if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                            needed.add(Manifest.permission.BLUETOOTH_SCAN);
                        }
                    }
                    if (!needed.isEmpty()) {
                        ActivityCompat.requestPermissions(MainActivity.this, needed.toArray(new String[0]), 101);
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    @Override
    protected void onDestroy() {
        if (usbReceiverRegistered) {
            try {
                unregisterReceiver(usbPermissionReceiver);
            } catch (Exception ignored) {}
            usbReceiverRegistered = false;
        }
        usbExecutor.shutdown();
        super.onDestroy();
    }
}
