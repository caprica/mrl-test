package test;

import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import fi.iki.elonen.NanoHTTPD;
import uk.co.caprica.vlcj.binding.internal.libvlc_callback_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_event_manager_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_event_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_instance_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_media_player_t;
import uk.co.caprica.vlcj.binding.internal.libvlc_media_t;
import uk.co.caprica.vlcj.binding.internal.media_parsed_changed;
import uk.co.caprica.vlcj.binding.support.strings.NativeString;
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery;
import uk.co.caprica.vlcj.media.MediaParsedStatus;
import uk.co.caprica.vlcj.media.Meta;
import uk.co.caprica.vlcj.media.ParseFlag;

import java.io.File;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static uk.co.caprica.vlcj.binding.internal.libvlc_event_e.libvlc_MediaParsedChanged;
import static uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_event_attach;
import static uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_event_detach;
import static uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_media_event_manager;
import static uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_media_get_meta;
import static uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_media_get_mrl;
import static uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_media_new_location;
import static uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_media_new_path;
import static uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_media_parse_request;
import static uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_media_player_new_from_media;
import static uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_media_player_play;
import static uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_media_release;
import static uk.co.caprica.vlcj.binding.lib.LibVlc.libvlc_new;
import static uk.co.caprica.vlcj.media.MediaParsedStatus.DONE;
import static uk.co.caprica.vlcj.media.MediaParsedStatus.mediaParsedStatus;

public class MrlTest {

    private static char sep = File.separatorChar;

    private static NanoHTTPD webServer;

    private static libvlc_instance_t instance;

    public static void main(String[] args) throws Exception {
        System.setProperty("jna.encoding", "UTF-8");

        webServer = new NanoHTTPD(5001) {
            @Override
            public Response serve(IHTTPSession session) {
                String uri = session.getUri();
                try {
                    InputStream in = getClass().getResourceAsStream(uri);
                    if (in == null) {
                        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 Not Found");
                    }
                    return newChunkedResponse(Response.Status.OK, "audio/mpeg", in);
                } catch (Exception e) {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "500 Internal Server Error");
                }
            }
        };
        webServer.start();

        new NativeDiscovery().discover();

        instance = libvlc_new(1, new StringArray(new String[] {"--quiet"}));

        // Normal files
        testFilePath("sample.mp3");
        testFilePath("образец.mp3");
        testFilePath("サンプル.mp3");

        // Normal files with spaces
        testFilePath("dir with space" + sep + "sample.mp3");
        testFilePath("dir with space" + sep + "образец.mp3");
        testFilePath("dir with space" + sep + "サンプル.mp3");

        // Normal file URLs
        testFileUrl("sample.mp3");
        testFileUrl("образец.mp3");
        testFileUrl("サンプル.mp3");

        // Encoding is not necessary, but it still works
        testFileUrl(URLEncoder.encode("sample.mp3", StandardCharsets.UTF_8));
        testFileUrl(URLEncoder.encode("образец.mp3", StandardCharsets.UTF_8));
        testFileUrl(URLEncoder.encode("サンプル.mp3", StandardCharsets.UTF_8));

        // These cases with spaces or other special characters should NOT be supported because it's up to the client application to send a valid URL, encoded if need be
        testFileUrl(URLEncoder.encode("dir with space/sample.mp3", StandardCharsets.UTF_8).replace("+", "%20"));
        testFileUrl(URLEncoder.encode("dir with space/образец.mp3", StandardCharsets.UTF_8).replace("+", "%20"));
        testFileUrl(URLEncoder.encode("dir with space/サンプル.mp3", StandardCharsets.UTF_8).replace("+", "%20"));

        // HTTP URLs
        testUrl("sample.mp3");
        testUrl("образец.mp3");
        testUrl("サンプル.mp3");

        // Encoding is not necessary, but it still works
        testUrl(URLEncoder.encode("sample.mp3", StandardCharsets.UTF_8));
        testUrl(URLEncoder.encode("образец.mp3", StandardCharsets.UTF_8));
        testUrl(URLEncoder.encode("サンプル.mp3", StandardCharsets.UTF_8));

        webServer.stop();
    }

    private static void testFilePath(String mrl) throws Exception {
        String currentDir = System.getProperty("user.dir");
        String path = currentDir + sep + "src" + sep + "main" + sep + "resources" + sep + mrl;
        libvlc_media_t media = libvlc_media_new_path(path);
        testMedia(mrl, media);
    }

    private static void testFileUrl(String mrl) throws Exception {
        String currentDir = System.getProperty("user.dir");
        // URL's always have forward-flash, even on Windows
        currentDir = currentDir.replace("\\", "/");
        if (!currentDir.startsWith("/")) {
            currentDir = "/" + currentDir;
        }
        String location = "file://" + currentDir + "/src/main/resources" + "/" + mrl;
        System.out.println(location);
        libvlc_media_t media = libvlc_media_new_location(location);
        testMedia(mrl, media);
    }

    private static void testUrl(String mrl) throws Exception {
        String location = "http://localhost:5001/" + mrl;
        System.out.println(location);
        libvlc_media_t media = libvlc_media_new_location(location);
        testMedia(mrl, media);
    }

    private static void testMedia(String mrl, libvlc_media_t media) throws Exception {
        libvlc_event_manager_t eventManager = libvlc_media_event_manager(media);

        AtomicReference<MediaParsedStatus> parseResult = new AtomicReference<>();

        CountDownLatch parseResultLatch = new CountDownLatch(1);

        libvlc_callback_t callback = new libvlc_callback_t() {
            @Override
            public void callback(libvlc_event_t event, Pointer userData) {
                if (event.type != libvlc_MediaParsedChanged.intValue()) {
                    return;
                }
                MediaParsedStatus parseStatus = mediaParsedStatus(((media_parsed_changed) event.u.getTypedValue(media_parsed_changed.class)).new_status);
                switch (parseStatus) {
                    case NONE:
                    case PENDING:
                        return;
                    case SKIPPED:
                    case FAILED:
                    case TIMEOUT:
                    case CANCELLED:
                    case DONE:
                        parseResult.set(parseStatus);
                        break;
                }
                parseResultLatch.countDown();
            }
        };

        libvlc_event_attach(eventManager, libvlc_MediaParsedChanged.intValue(), callback, null);

        String nativeMrl = NativeString.copyAndFreeNativeString(libvlc_media_get_mrl(media));

        // Parsing does not work for http, so we need to actually play (see later) the media to check it
        if (!nativeMrl.startsWith("http://")) {
            libvlc_media_parse_request(instance, media, ParseFlag.PARSE_LOCAL.intValue() | ParseFlag.PARSE_NETWORK.intValue(), 3000);
            libvlc_media_parse_request(instance, media, ParseFlag.PARSE_LOCAL.intValue(), 3000);

            parseResultLatch.await();
        }

        boolean success = false;

        if (parseResult.get() == DONE) {
            if (!nativeMrl.startsWith("http://")) {
                Pointer metaResult = libvlc_media_get_meta(media, Meta.ARTIST.intValue());
                String meta = NativeString.copyAndFreeNativeString(metaResult);
                success = meta.equals("Caprica Software Limited");
            } else {
                success = true;
            }
        }

        libvlc_event_detach(eventManager, libvlc_MediaParsedChanged.intValue(), callback, null);

        libvlc_media_player_t mp = libvlc_media_player_new_from_media(instance, media);

        if (nativeMrl.startsWith("http://")) {
            libvlc_media_player_play(mp);
            Thread.sleep(10000);
            success = true;
        }

        libvlc_media_release(media);

        System.out.printf("%s: %s%n", success ? "PASS" : "FAIL", mrl);
    }
}
