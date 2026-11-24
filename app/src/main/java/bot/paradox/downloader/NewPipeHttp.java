package bot.paradox.downloader;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** HTTP transport for NewPipe Extractor, on plain HttpURLConnection. */
final class NewPipeHttp extends Downloader {
    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        HttpURLConnection c = (HttpURLConnection) new URL(request.url()).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            c.setRequestMethod(request.httpMethod());
            c.setRequestProperty("User-Agent", Http.UA);
            for (Map.Entry<String, List<String>> h : request.headers().entrySet()) {
                boolean first = true;
                for (String v : h.getValue()) {
                    if (first) c.setRequestProperty(h.getKey(), v);
                    else c.addRequestProperty(h.getKey(), v);
                    first = false;
                }
            }
            byte[] body = request.dataToSend();
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = c.getResponseCode();
            if (code == 429) throw new ReCaptchaException("Rate limited", request.url());

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (in != null) {
                try (InputStream is = in) {
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = is.read(b)) != -1) buf.write(b, 0, n);
                }
            }
            Map<String, List<String>> headers = new HashMap<>();
            for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                if (h.getKey() != null) headers.put(h.getKey(), h.getValue());
            }
            return new Response(code, c.getResponseMessage(), headers, buf.toString("UTF-8"), c.getURL().toString());
        } finally {
            c.disconnect();
        }
    }
}
