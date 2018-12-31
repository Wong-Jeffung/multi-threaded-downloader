import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadTask {
    private URL url;
    private boolean resumable;
    private int fileSize = 0;
    private int[] downLoadRange;
    private File localFile;
    private boolean multithreaded = true;
    private Object lockProvider = new Object();
    private AtomicInteger downloadedBytes = new AtomicInteger(0);
    private AtomicInteger aliveThreads = new AtomicInteger(0);
    private int THREAD_NUM = 5;
    private int TIME_OUT = 5000;
    //private final int MIN_SIZE = 2 << 20;

    public DownloadTask(String url, String localPath) throws MalformedURLException {
        this.url = new URL(url);
        this.localFile = new File(localPath);
    }

    public DownloadTask(String url, String localPath, int threadNum, int timeOut) throws MalformedURLException {
        this.url = new URL(url);
        this.localFile = new File(localPath);
        this.THREAD_NUM = threadNum;
        this.TIME_OUT = timeOut;
    }

    //开始下载
    public void startDownload() throws IOException {
        resumable = supportResumeDownload();
        if (!resumable || THREAD_NUM == 1) {
            multithreaded = false;
        }
        if (multithreaded) {
            创建多个线程下载
        } else {
            创建一个线程下载
        }

        startDownLoadMonitor(); //守护线程

        try {
            synchronized (lockProvider) {
                lockProvider.wait();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        mergeTempFile();
    }

    public boolean supportResumeDownload() throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Range","bytes=0-");
        int resCode = 0;
        try {
            conn.connect();
            fileSize = conn.getContentLength();
            resCode = conn.getResponseCode();
        }catch (ConnectException e){
            System.out.println("http连接出问题");
        }finally {
            conn.disconnect();
        }
        if(resCode == 206){
            System.out.println("该文件支持断点续传");
            return true;
        }else{
            System.out.println("该文件不支持断点续传");
            return false;
        }
    }

    class downLoadThread implements Runnable{
        private int id;
        private int bebinByte;
        private int endByte;
        private OutputStream outputStream;
        private InputStream inputStream;

        public downLoadThread(int id,int bebinByte,int endByte){
            this.id = id;
            this.bebinByte = bebinByte;
            this.endByte = endByte;
        }
        @Override
        public void run() {

        }
    }


}
