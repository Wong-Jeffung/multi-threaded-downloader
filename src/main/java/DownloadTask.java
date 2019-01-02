import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.log4j.Logger;

public class DownloadTask {
    private static  final Logger LOGGER = Logger.getLogger(DownloadTask.class);

    private URL url;
    private boolean resumable;
    private int fileSize = 0;
    private int[] downLoadRange;
    private File localFile;
    private boolean multithreaded = true;
    private final Object  lockProvider = new Object();
    private AtomicInteger downloadedBytes = new AtomicInteger(0);
    private AtomicInteger aliveDownLoadThreads = new AtomicInteger(0);
    private int threadNum = 5;
    private int timeOut = 5000;
    private File tempFile = new File("/home/linuxprobe/temp");
    private int sleepTime;//睡眠时间，用于限速
    //private final int MIN_SIZE = 2 << 20;

    public DownloadTask(String url, String localPath, String tempPath, int sleepTime) throws MalformedURLException {
        this.url = new URL(url);
        this.localFile = new File(localPath);
        this.tempFile = new File(tempPath);
        this.sleepTime = sleepTime;
    }

    public DownloadTask(String url, String localPath, String tempPath, int threadNum, int timeOut, int sleepTime) throws MalformedURLException {
        this.url = new URL(url);
        this.localFile = new File(localPath);
        this.tempFile = new File(tempPath);
        this.threadNum = threadNum;
        this.timeOut = timeOut;
        this.sleepTime = sleepTime;
    }

    //开始下载
    public void startDownload() throws IOException {
        long startTime = System.currentTimeMillis();

        resumable = supportResumeDownload();
        if (!resumable || threadNum == 1) {
            multithreaded = false;
        }
        if (multithreaded) {
            downLoadRange = new int[threadNum + 1];
            int partSize = fileSize / threadNum;
            for(int i = 0; i < threadNum; i++){
                downLoadRange[i] = i * partSize;
            }
            downLoadRange[threadNum] = fileSize;
            for(int i = 0; i < threadNum; i++){
                new downLoadThread(i+1,downLoadRange[i],downLoadRange[i+1] - 1).start();
                aliveDownLoadThreads.addAndGet(1);
            }
        } else {
            downLoadRange = new int[1];
            new downLoadThread(1,0,fileSize - 1).start();
            aliveDownLoadThreads.addAndGet(1);
        }

        startDownLoadMonitor(); //守护线程

        try {
            synchronized (lockProvider) {
                lockProvider.wait();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        long timeSpend = System.currentTimeMillis() - startTime;
        LOGGER.info("文件下载成功");
        LOGGER.info(String.format("花费时间: %.3f s, 平均速度: %d KB/s",
                timeSpend / 1000.0, (downloadedBytes.get() >> 10)  / (timeSpend / 1000)));

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
            LOGGER.error("http连接出问题");
        }finally {
            conn.disconnect();
        }
        if(resCode == 206){
            LOGGER.info("该文件支持断点续传");
            return true;
        }else{
            LOGGER.info("该文件不支持断点续传");
            return false;
        }
    }

    public void startDownLoadMonitor(){
        Thread downloadMonitor = new Thread(() -> {
            int preDownloads = 0;
            int currDownloads;
            while(true){
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                currDownloads = downloadedBytes.get();
                LOGGER.info(String.format("速度: %d KB/s,以下载: %d KB (%.2f%%),存在线程: %d",
                        (currDownloads - preDownloads) >> 10,currDownloads >> 10,currDownloads / (float)fileSize * 100,aliveDownLoadThreads.get()));

                preDownloads = currDownloads;

                if(aliveDownLoadThreads.get() == 0){
                    synchronized (lockProvider){
                        lockProvider.notifyAll();
                    }
                }
            }
        });
        downloadMonitor.setDaemon(true);
        downloadMonitor.start();
    }

    public  void mergeTempFile(){
        try {
            //判断文件是否存在，用下面的方式创建文件输出流需要定位到具体文件
            if(!localFile.exists()){
                localFile.getParentFile().mkdir();
                localFile.createNewFile();
            }
            OutputStream outputStream = new FileOutputStream(localFile);
            byte[] buffer = new byte[1024];
            int partSize;
            for(int i = 0; i < threadNum; i++){
                InputStream inputStream = new FileInputStream(tempFile.getAbsolutePath() + "/" + "temp" + (i+1) + ".tmp");
                while((partSize = inputStream.read(buffer)) != -1){
                    outputStream.write(buffer,0,partSize);
                    outputStream.flush();
                }
                inputStream.close();
            }
            outputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    class downLoadThread extends Thread{
        private int id;
        private int beginByte;
        private int endByte;
        private OutputStream outputStream;
        private InputStream inputStream;

        private downLoadThread(int id,int beginByte,int endByte){
            this.id = id;
            this.beginByte = beginByte;
            this.endByte = endByte;
        }

        @Override
        public void run() {
           boolean success = false;
           while(true){
               try {
                   success = downLoad();
               } catch (IOException e) {
                   e.printStackTrace();
               }
               if(success){
                   LOGGER.info("已下载第" + id + "部分");
                   break;
               }else{
                   LOGGER.info("重新下载第" + id + "部分");
               }
           }
           aliveDownLoadThreads.decrementAndGet();
        }

        private boolean downLoad() throws IOException {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Range",String.format("bytes=%d-%d",beginByte,endByte));
            conn.setConnectTimeout(timeOut);
            conn.setReadTimeout(timeOut);
            try{
                conn.connect();
                int partSize = conn.getHeaderFieldInt("Content-Length", -1);
                if(partSize != endByte - beginByte + 1){
                    return false;
                }
               outputStream = new FileOutputStream(tempFile.getAbsolutePath() + "/" + "temp" + id + ".tmp");
               inputStream = conn.getInputStream();
               byte[] buffer = new byte[1024];
               int size;
               while(beginByte <= endByte && (size = inputStream.read(buffer)) > 0){
                   beginByte += size;
                   downloadedBytes.addAndGet(size);
                   outputStream.write(buffer,0,size);
                   outputStream.flush();
                   Thread.sleep(sleepTime);//实现限速。。
               }
               outputStream.close();
            }catch(SocketTimeoutException e) {
                LOGGER.error("Part" + (id) + " Reading timeout.");
                return false;
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                conn.disconnect();
            }
            return true;
        }
    }
}
