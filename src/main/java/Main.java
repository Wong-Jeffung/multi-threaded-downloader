import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        String url = "http://mirrors.163.com/debian/ls-lR.gz";
        DownloadTask downloadTask = new DownloadTask(url,"D:/target/ls-lR.gz",5,5000);
        downloadTask.startDownload();
    }
}
