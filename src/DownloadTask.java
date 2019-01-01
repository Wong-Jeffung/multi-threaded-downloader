import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadTask {
    private URL url;
    private boolean resumable;
    private int fileSize = 0;
    private int[] downLoadRange;
    private File localFile;
    private boolean multithreaded = true;
    private final Object  lockProvider = new Object();
    private AtomicInteger downloadedBytes = new AtomicInteger(0);
    private AtomicInteger aliveDownLoadThreads = new AtomicInteger(0);
    private int THREAD_NUM = 5;
    private int TIME_OUT = 5000;
    private File TEMP_FILE = new File("D:/temp");
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
        long startTime = System.currentTimeMillis();

        resumable = supportResumeDownload();
        if (!resumable || THREAD_NUM == 1) {
            multithreaded = false;
        }
        if (multithreaded) {
            downLoadRange = new int[THREAD_NUM + 1];
            int partSize = fileSize / THREAD_NUM;
            for(int i = 0;i < THREAD_NUM;i++){
                downLoadRange[i] = i * partSize;
            }
            downLoadRange[THREAD_NUM] = fileSize;
            for(int i = 0;i < THREAD_NUM;i++){
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
        System.out.println("文件下载成功");
        System.out.println(String.format("花费时间: %.3f s, 平均速度: %d KB/s",
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
                System.out.println(String.format("速度: %d KB/s,以下载: %d KB (%.2f%%),存在线程: %d",
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
            for(int i = 0; i < THREAD_NUM;i++){
                InputStream inputStream = new FileInputStream(TEMP_FILE.getAbsolutePath() + "/" + "temp" + (i+1) + ".tmp");
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
                   System.out.println("已下载第" + id + "部分");
                   break;
               }else{
                   System.out.println("重新下载第" + id + "部分");
               }
           }
           aliveDownLoadThreads.decrementAndGet();
        }

        private boolean downLoad() throws IOException {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Range",String.format("bytes=%d-%d",beginByte,endByte));
            conn.setConnectTimeout(TIME_OUT);
            conn.setReadTimeout(TIME_OUT);
            try{
                conn.connect();
                int partSize = conn.getHeaderFieldInt("Content-Length", -1);
                if(partSize != endByte - beginByte + 1){
                    return false;
                }
               outputStream = new FileOutputStream(TEMP_FILE.getAbsolutePath() + "/" + "temp" + id + ".tmp");
               inputStream = conn.getInputStream();
               byte[] buffer = new byte[1024];
               int size;
               while(beginByte <= endByte && (size = inputStream.read(buffer)) > 0){
                   beginByte += size;
                   downloadedBytes.addAndGet(size);
                   outputStream.write(buffer,0,size);
                   outputStream.flush();
                   Thread.sleep(20);//实现限速。。
               }
               outputStream.close();
            }catch(SocketTimeoutException e) {
                System.out.println("Part" + (id) + " Reading timeout.");
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
