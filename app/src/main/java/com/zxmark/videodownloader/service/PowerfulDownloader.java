package com.zxmark.videodownloader.service;

import com.zxmark.videodownloader.downloader.DownloadingTaskList;
import com.zxmark.videodownloader.spider.HttpRequestSpider;
import com.zxmark.videodownloader.util.CpuUtils;
import com.zxmark.videodownloader.util.DownloadUtil;
import com.zxmark.videodownloader.util.LogUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/**
 * Created by fanlitao on 17/6/9.
 * <p>
 * 支持多线程同时下载同一个文件，加快文件的下载速度
 */

public class PowerfulDownloader {


    public static final int CODE_OK = 0;
    public static final int CODE_DOWNLOAD_FAILED = -1;
    public volatile static PowerfulDownloader sInstance;

    private static volatile boolean sIntrupted = false;

    private Object mSyncedLocked = new Object();

    private volatile boolean mMutilThreadDownloading = false;

    private boolean mSyncedLockedWaiting = false;

    public int THREAD_COUNT = 5;

    private volatile int mReadBytesCount = 0;
    private IPowerfulDownloadCallback mCallback;

    private PowerfulDownloader() {
        final int cpuCount = CpuUtils.getNumberOfCPUCores();
        THREAD_COUNT = cpuCount + 1;
    }


    public static PowerfulDownloader getDefault() {
        synchronized (PowerfulDownloader.class) {
            if (sInstance == null) {
                sInstance = new PowerfulDownloader();
            }
        }
        return sInstance;
    }


    public void interupted() {
        sIntrupted = true;
    }

    public void startDownload(String fileUrl, IPowerfulDownloadCallback callback) {
        mCallback = callback;
        sIntrupted = false;
        download(fileUrl, DownloadUtil.getDownloadTargetInfo(fileUrl), THREAD_COUNT);
    }

    /**
     * 下载文件
     */
    private void download(String fileUrl, String targetPath, int threadNum) {
        LogUtil.v("download", "fileUrl:" + fileUrl + ":" + threadNum);
        int codeStatus = CODE_OK;
        CountDownLatch latch = new CountDownLatch(threadNum);
        mReadBytesCount = 0;
        HttpURLConnection conn = null;
        int targetFileSize = 0;
        ExecutorService executorService = DownloadingTaskList.SINGLETON.getExecutorService();
        try {
            //通过下载路径获取连接
            URL url = new URL(fileUrl);
            conn = (HttpURLConnection) url.openConnection();
            //设置连接的相关属性
            conn.setRequestMethod(HttpRequestSpider.METHOD_GET);
            conn.setReadTimeout(HttpRequestSpider.CONNECTION_TIMEOUT);
            //判断连接是否正确。
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                // 获取文件大小。
                int fileSize = conn.getContentLength();
                targetFileSize = fileSize;
                //得到文件名
                //根据文件大小及文件名，创建一个同样大小，同样文件名的文件
                File file = new File(targetPath);
                RandomAccessFile raf = new RandomAccessFile(file, "rw");
                raf.setLength(fileSize);
                raf.close();
                // 将文件分成threadNum = 5份。
                int block = fileSize % threadNum == 0 ? fileSize / threadNum
                        : fileSize / threadNum + 1;
                for (int threadId = 0; threadId < threadNum; threadId++) {
                    //传入线程编号，并开始下载。
                    executorService.execute(new DownloadPartialFileRunnable(threadId, fileSize, block, file, url, latch));
                }
                LogUtil.v("download", "wait all downloading thread");
                latch.await();
                mReadBytesCount = 0;
            }

        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        if (mCallback != null) {
            if (new File(targetPath).length() < targetFileSize) {
                codeStatus = CODE_DOWNLOAD_FAILED;
            }
            mCallback.onFinish(codeStatus, targetPath);
        }


        LogUtil.v("download", "all thread executed finished");
    }

    class DownloadPartialFileRunnable implements Runnable {

        int start, end, threadId;
        File file = null;
        URL url = null;
        CountDownLatch latch;
        String filePath;
        int fileSize;

        public DownloadPartialFileRunnable(int threadId, int fileSize, int block, File file, URL url, CountDownLatch latch) {
            this.threadId = threadId;
            start = block * threadId;
            end = block * (threadId + 1) - 1;
            this.file = file;
            this.url = url;
            this.latch = latch;
            filePath = file.getAbsolutePath();
            this.fileSize = fileSize;
        }

        @Override
        public void run() {
            HttpURLConnection conn = null;
            InputStream inputStream = null;
            try {
                //获取连接并设置相关属性。
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(HttpRequestSpider.METHOD_GET);
                conn.setReadTimeout(HttpRequestSpider.CONNECTION_TIMEOUT);
                //此步骤是关键。
                conn.setRequestProperty("Range", "bytes=" + start + "-" + end);
                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                    RandomAccessFile raf = new RandomAccessFile(file, "rw");
                    //移动指针至该线程负责写入数据的位置。
                    raf.seek(start);
                    //读取数据并写入
                    inputStream = conn.getInputStream();
                    byte[] b = new byte[1024];
                    int len = 0;
                    while ((len = inputStream.read(b)) != -1) {
                        if (sIntrupted) {
                            break;
                        }
                        mReadBytesCount += len;
                        if (mCallback != null) {
                            mCallback.onProgress(filePath, (int) (1 + 100 * (mReadBytesCount * 1.0f / fileSize)));
                        }
                        raf.write(b, 0, len);
                    }
                    LogUtil.v("download", "线程:" + threadId + ":下载完毕");
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                latch.countDown();
                if (conn != null) {
                    conn.disconnect();
                }
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    //文件下载线程
    class DownloadThread extends Thread {
        int start, end, threadId;
        File file = null;
        URL url = null;
        CountDownLatch latch;
        String filePath;
        int fileSize;

        public DownloadThread(int threadId, int fileSize, int block, File file, URL url, CountDownLatch latch) {
            this.threadId = threadId;
            start = block * threadId;
            end = block * (threadId + 1) - 1;
            this.file = file;
            this.url = url;
            this.latch = latch;
            filePath = file.getAbsolutePath();
            this.fileSize = fileSize;
        }

        public void run() {

        }
    }


    public interface IPowerfulDownloadCallback {
        void onStart(String path);

        void onFinish(int statusCode, String path);

        void onError(int errorCode);

        void onProgress(String path, final int progress);
    }
}