// SPDX-License-Identifier: MPL-2.0
package cctrun.live;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpPolicyTest {
    @Test void hostAndBearerChecksFailClosed() {
        assertFalse(HttpPolicy.hostAllowed(null,123));assertFalse(HttpPolicy.hostAllowed("evil:123",123));
        assertTrue(HttpPolicy.hostAllowed("127.0.0.1:123",123));
        var token="secret".getBytes(StandardCharsets.UTF_8);
        assertFalse(HttpPolicy.authorised(null,token));assertFalse(HttpPolicy.authorised("Bearer wrong",token));
        assertTrue(HttpPolicy.authorised("Bearer secret",token));
    }
    @Test void slowBodyIsClosedAndOnlyWorkerCanServeNextRequest() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var worker=Executors.newSingleThreadExecutor();var timer=Executors.newSingleThreadScheduledExecutor();
        var finished=new Semaphore(0);
        server.setExecutor(worker);
        server.createContext("/",exchange->{
            try {
                if(exchange.getRequestMethod().equals("POST")) {
                    try {
                        if(exchange.getRequestURI().getPath().equals("/reject")) HttpPolicy.discard(exchange,200,timer);
                        else HttpPolicy.read(exchange,32,200,timer);
                    }catch(HttpPolicy.ReadTimeout expected){finished.release();}
                }else {exchange.sendResponseHeaders(200,-1);}
            }finally {exchange.close();}
        });server.start();
        try {
            for(String route:new String[]{"/", "/reject"}) {
                try(var socket=new Socket("127.0.0.1",server.getAddress().getPort())) {
                    socket.setSoTimeout(3000);
                    socket.getOutputStream().write(("POST "+route+" HTTP/1.1\r\nHost: localhost\r\nContent-Length: 10\r\n\r\nx").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    assertEquals(-1,socket.getInputStream().read());
                    assertTrue(finished.tryAcquire(3,TimeUnit.SECONDS));
                    var client=java.net.http.HttpClient.newHttpClient();
                    assertEquals(200,client.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/")).GET().timeout(java.time.Duration.ofSeconds(3)).build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode());
                }
            }
        }finally {server.stop(0);worker.shutdownNow();timer.shutdownNow();}
    }
}
