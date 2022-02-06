package com.tungsten.hmclpe.task;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.util.ArrayMap;
import android.util.Log;

import com.tungsten.hmclpe.launcher.list.install.DownloadTaskListBean;
import com.tungsten.hmclpe.utils.download.SSLSocketClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class DownloadTask extends AsyncTask<Map<String, String>, Integer, Map<String, String>> {

    private final WeakReference<Context> ctx;
    private Map<String, String> failedFile;//下载失败的文件
    private final Feedback feedback;//回调
    private int maxTask = 8;//最大任务数

    public abstract static class Feedback {
        public abstract void addTask(DownloadTaskListBean bean);
        public abstract void updateProgress(DownloadTaskListBean bean);
        public abstract void updateSpeed(String speed);
        public abstract void removeTask(DownloadTaskListBean bean);
        public abstract void onFinished(Map<String, String> failedFile);
        public abstract void onCancelled();
    }

    public DownloadTask(Context ctx, Feedback feedback) {
        this.ctx = new WeakReference<>(ctx);
        this.feedback = feedback;
        failedFile = new ArrayMap<>();
    }

    public void setMaxTask(int maxTask){
        this.maxTask = maxTask;
    }

    @Override
    public void onPreExecute() {

    }

    //这里多加了几个回调
    @SuppressLint("WrongThread")
    @Override
    public Map<String, String> doInBackground(Map<String, String>... args) {
        Map<String, String> tasks = args[0];
        List<String> urls = new ArrayList<>();
        urls.addAll(tasks.keySet());
        BlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(maxTask);
        ExecutorService threadPool = new ThreadPoolExecutor(maxTask, maxTask,
                0, TimeUnit.SECONDS,
                workQueue,
                new ThreadPoolExecutor.DiscardPolicy());
        for (int j = 0; j < urls.size(); j++) {
            String url = urls.get(j);
            String path = tasks.get(url);
            threadPool.execute(() -> {
                if (!new File(path).exists()) {
                    int tryTimes = 5;
                    for (int i = 0; i < tryTimes; i++) {
                        if (isCancelled()) {
                            threadPool.shutdownNow();
                            return;
                        }
                        DownloadTaskListBean bean = new DownloadTaskListBean(path.substring(path.lastIndexOf("/")+ 1),
                                url,path);
                        try {
                            feedback.addTask(bean);
                            downloadFileMonitored(url, path, new DownloadFeedback() {
                                @Override
                                public void updateProgress(long curr, long max) {
                                    long progress = 100 * curr / max;
                                    bean.progress = (int) progress;
                                    feedback.updateProgress(bean);
                                }

                                @Override
                                public void updateSpeed(String speed) {
                                    feedback.updateSpeed(speed);
                                }
                            });
                            feedback.removeTask(bean);
                            break;
                        } catch (Exception e) {
                            e.printStackTrace();
                            if (i == tryTimes-1) {
                                failedFile.put(url, path);
                            }
                            feedback.removeTask(bean);
                        }
                    }
                }
            });
            while (!workQueue.isEmpty()) {
                ;
            }
            int progress = (j + 1) * 100 / urls.size();
            onProgressUpdate(progress);
        }
        threadPool.shutdown();
        try {
            threadPool.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return failedFile;
    }

    @Override
    protected void onProgressUpdate(Integer... p1) {

    }

    @Override
    public void onPostExecute(Map<String, String> result) {
        Set<String> keys = result.keySet();
        for (String key : keys) {
            Log.e("url", key);
            Log.e("path", result.get(key));
        }
        feedback.onFinished(result);
    }

    @Override
    protected void onCancelled(Map<String, String> result) {
        feedback.onCancelled();
    }

    public abstract static class DownloadFeedback {
        public abstract void updateProgress(long curr, long max);
        public abstract void updateSpeed(String speed);
    }

    //这个方法只把连接超时改成了8秒，其他啥也没动
    public static void downloadFileMonitored(String url,String nameOutput, DownloadFeedback monitor) throws IOException {
        File nameOutputFile = new File(nameOutput);
        if (!nameOutputFile.exists()) {
            nameOutputFile.getParentFile().mkdirs();
        }
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.sslSocketFactory(SSLSocketClient.getSSLSocketFactory(),(X509TrustManager)SSLSocketClient.getTrustManager()[0]);
        builder.hostnameVerifier(SSLSocketClient.getHostnameVerifier());
        builder.connectTimeout(8, TimeUnit.SECONDS);
        builder.readTimeout(8, TimeUnit.SECONDS);
        builder.writeTimeout(8, TimeUnit.SECONDS);
        builder.followRedirects(true);
        builder.followSslRedirects(true);
        OkHttpClient okHttpClient = builder.build();
        Request request = new Request.Builder()
                .url(url)
                //.addHeader("User-Agent","Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/97.0.4692.99 Safari/537.36")
                .build();
        Call call = okHttpClient.newCall(request);
        Response response = call.execute();
        ResponseBody responseBody = response.body();
        InputStream readStr = responseBody.byteStream();
        FileOutputStream fos = new FileOutputStream(nameOutputFile);
        int cur = 0;
        int oval = 0;
        long len = responseBody.contentLength();
//        System.out.println(len);
        byte[] buf = new byte[65535];
        long lastTime=System.currentTimeMillis();
        long lastLen=0;
        while ((cur = readStr.read(buf)) != -1) {
            oval += cur;
            if (System.currentTimeMillis()-lastTime>=1000){
                lastLen=oval;
                lastTime=System.currentTimeMillis();
                monitor.updateSpeed(formetFileSize(oval-lastLen)+"/s");
            }
            fos.write(buf, 0, cur);
            monitor.updateProgress(oval, len);
        }
        fos.close();
        readStr.close();
    }

    public static String formetFileSize(long fileS) {
        DecimalFormat df = new DecimalFormat("#.00");
        String fileSizeString = "";
        if (fileS < 1024) {
            fileSizeString = df.format((double) fileS) + "B";
        } else if (fileS < 1048576) {
            fileSizeString = df.format((double) fileS / 1024) + "K";
        } else if (fileS < 1073741824) {
            fileSizeString = df.format((double) fileS / 1048576) + "M";
        } else {
            fileSizeString = df.format((double) fileS / 1073741824) + "G";
        }
        return fileSizeString;
    }
}