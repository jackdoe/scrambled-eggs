// Copyright (c) <2017> <Matheus Villela>
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package com.scrambledeggs.flutter.unscramble.qrcodereader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Base64;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;

import org.tukaani.xz.XZInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;

public class QRScanActivity extends Activity implements QRCodeReaderView.OnQRCodeReadListener {
    final static int TYPE_META = 0;
    final static int TYPE_TEXT = 1;
    public static String EXTRA_RESULT = "extra_result";
    public static String EXTRA_NAME = "extra_name";
    public static String EXTRA_CONTENT_TYPE = "extra_content_type";
    public static String EXTRA_FOCUS_INTERVAL = "extra_focus_interval";
    public static String EXTRA_FORCE_FOCUS = "extra_force_focus";
    public static String EXTRA_TORCH_ENABLED = "extra_torch_enabled";
    long t0;
    CRC32 crc = new CRC32();
    private boolean qrRead;
    private QRCodeReaderView view;
    private TextView progress;
    private Item item;

    private static String bytesToHex(byte[] hash) {
        StringBuffer hexString = new StringBuffer();
        for (int i = 0; i < hash.length; i++) {
            String hex = Integer.toHexString(0xff & hash[i]);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private static byte[] decompress(String compression, byte[] input) throws Exception {
        if (compression == null || compression.equals("gzip")) {
            return decompressGzip(input);
        } else if (compression.equals("xz")) {
            return decompressXZ(input);
        } else if (compression.equals("none")) {
            return input;
        } else {
            // assume some unknown compression, just return the bytes
            return input;
        }
    }

    private static byte[] decompressIS(InputStream gis, byte[] input) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gis.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            gis.close();
            out.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] decompressGzip(byte[] input) throws Exception {
        GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(input));
        return decompressIS(gis, input);
    }

    private static byte[] decompressXZ(byte[] input) throws Exception {
        XZInputStream gis = new XZInputStream(new ByteArrayInputStream(input));
        return decompressIS(gis, input);
    }

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        item = new Item();
        setContentView(R.layout.activity_qr_read);
        view = (QRCodeReaderView) findViewById(R.id.activity_qr_read_reader);
        Map<DecodeHintType, Object> hints = new HashMap<>();
        List<BarcodeFormat> allowed = new ArrayList<>();
        allowed.add(BarcodeFormat.QR_CODE);
        //hints.put(DecodeHintType.PURE_BARCODE, false);
        //hints.put(DecodeHintType.TRY_HARDER,true);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, allowed);
        view.setDecodeHints(hints);

        progress = (TextView) findViewById(R.id.text);
        Intent intent = getIntent();
        view.setOnQRCodeReadListener(this);


        if (intent.getBooleanExtra(EXTRA_FORCE_FOCUS, false)) {
            view.forceAutoFocus();
        }

        view.setAutofocusInterval(3530);
        view.setTorchEnabled(intent.getBooleanExtra(EXTRA_TORCH_ENABLED, false));
    }

    @Override
    public void onQRCodeRead(Result[] codes) {
        if (t0 == 0) {
            t0 = System.currentTimeMillis();
        }
        if (!qrRead) {
            for (Result code : codes) {
                try {
                    String text = code.getText();
                    crc.reset();
                    String[] splitted = text.split(":", 5);
                    byte[] value;
                    int type, id, outOf;

                    if (splitted.length == 3) {
                        long crc32 = Long.parseLong(splitted[0]);
                        crc.update(splitted[1].getBytes());
                        crc.update(':');
                        crc.update(splitted[2].getBytes());
                        if (crc.getValue() != crc32) {
                            //System.out.println(String.format("bad crc, got %d expected %d", crc.getValue(), crc32));
                            return;
                        }
                        String header = new String(Base64.decode(splitted[1], Base64.DEFAULT));
                        String svalue = splitted[2];
                        String[] splittedHeader = header.split(":");

                        try {
                            type = Integer.parseInt(splittedHeader[1]);
                            id = Integer.parseInt(splittedHeader[2]);
                            outOf = Integer.parseInt(splittedHeader[3]);
                            value = Base64.decode(svalue.replace("*", ""), Base64.DEFAULT);
                        } catch (Exception e) {
                            //e.printStackTrace();
                            return;
                        }
                    } else if (splitted.length == 5) {
                        long crc32 = Long.parseLong(splitted[0]);
                        crc.update(String.format("%s:%s:%s:%s", splitted[1], splitted[2], splitted[3], splitted[4]).getBytes());
                        if (crc.getValue() != crc32) {
                            //System.out.println(String.format("bad crc, got %d expected %d", crc.getValue(), crc32));
                            return;
                        }
                        try {
                            type = Integer.parseInt(splitted[1]);
                            id = Integer.parseInt(splitted[2]);
                            outOf = Integer.parseInt(splitted[3]);
                            value = Base64.decode(splitted[4].replace("*", ""), Base64.DEFAULT);
                        } catch (Exception e) {
                            return;
                        }
                    } else {
                        //System.out.println("bad count, expected 5 got " + splitted.length);
                        return;
                    }


                    if (type == TYPE_META) {
                        String[] meta = new String(value).split(":");

                        item.name = meta[0];
                        item.totalSHA = meta[1];
                        item.outOf = Integer.parseInt(meta[2]);
                        item.contentType = meta[3];
                        item.totalSize = Integer.parseInt(meta[4]);

                        if (meta.length >= 6) {
                            item.compression = meta[5];
                        }
                    } else if (type == TYPE_TEXT) {
                        item.chunks.put(id, value);
                    }

                    if (item.name != null && item.totalSHA != null && item.contentType != null && item.outOf > 0 && item.chunks.size() == item.outOf) {
                        // yey we have everything
                        Intent data = new Intent();
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        for (int i = 0; i < outOf; i++) {
                            byte[] b = item.chunks.get(i);
                            bo.write(b);
                        }
                        bo.flush();
                        bo.close();
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        String sha = bytesToHex(digest.digest(bo.toByteArray()));
                        if (!sha.equals(item.totalSHA)) {
                            throw new IllegalAccessException(String.format("sha mismatch: expected [%s], computed [%s]", item.totalSHA, sha));
                        }
                        data.putExtra(EXTRA_RESULT, decompress(item.compression, bo.toByteArray()));
                        data.putExtra(EXTRA_NAME, item.name);
                        data.putExtra(EXTRA_CONTENT_TYPE, item.contentType);
                        setResult(Activity.RESULT_OK, data);
                        finish();
                        item = new Item();
                        qrRead = true;
                    }
                    long took = System.currentTimeMillis() - t0;
                    if (took == 0)
                        took = 1;
                    long size = 0;

                    for (byte[] b : item.chunks.values())
                        size += b.length;

                    double speed = (double) (size * 8) / ((double) took / 1000D) / 1024D;
                    progress.setText(String.format("%.2fKB/%.2fKB, speed: %.2fkbps, %s %s %d/%d, compression: %s", size / 1024D, item.totalSize / 1024D, speed, item.name == null ? "?" : item.name, item.contentType == null ? "?" : item.contentType, item.chunks.size(), item.outOf == 0 ? outOf : item.outOf, item.compression == null ? "unknown" : item.compression));
                } catch (Exception e) {
                    item = new Item();
                    return;
                    //e.printStackTrace();
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.startCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.stopCamera();
    }

    public static class Item {
        public String name;
        public int outOf;
        public String totalSHA;
        public int totalSize;
        public String contentType;
        public Map<Integer, byte[]> chunks = new ConcurrentHashMap<>();
        public String compression;
    }
}