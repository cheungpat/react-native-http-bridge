package me.alwx.HttpServer;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.ReadableType;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import androidx.annotation.Nullable;
import android.util.Log;

public class Server extends NanoHTTPD {
    private static final String TAG = "HttpServer";
    private static final String SERVER_EVENT_ID = "httpServerResponseReceived";

    private ReactContext reactContext;
    private Map<String, Response> responses;

    public Server(ReactContext context, int port) {
        super(port);
        reactContext = context;
        responses = new HashMap<>();

        Log.d(TAG, "Server started");
    }

    @Override
    public Response serve(IHTTPSession session) {
        Log.d(TAG, "Request received!");

        Random rand = new Random();
        String requestId = String.format("%d:%d", System.currentTimeMillis(), rand.nextInt(1000000));

        WritableMap request;
        try {
            request = fillRequestMap(session, requestId);
        } catch (Exception e) {
            return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage()
            );
        }

        this.sendEvent(reactContext, SERVER_EVENT_ID, request);

        while (responses.get(requestId) == null) {
            try {
                Thread.sleep(10);
            } catch (Exception e) {
                Log.d(TAG, "Exception while waiting: " + e);
            }
        }
        Response response = responses.get(requestId);
        responses.remove(requestId);
        return response;
    }

    public void respond(String requestId, int code, String type, String body, ReadableMap headers) {
        byte[] data;
        Response response;
        try {
            data = Base64.getDecoder().decode(body);
            response = newFixedLengthResponse(Status.lookup(code), type, new ByteArrayInputStream(data), data.length);
        } catch (IllegalArgumentException ex) {
            response = newFixedLengthResponse(Status.lookup(502), type, "Bad Gateway");
        }

        if (headers != null) {
            ReadableMapKeySetIterator iterator = headers.keySetIterator();
            while (iterator.hasNextKey()) {
                String key = iterator.nextKey();
                ReadableType readableType = headers.getType(key);
                switch (readableType) {
                    case Null:
                        response.addHeader(key, "");
                        break;
                    case Boolean:
                        response.addHeader(key, String.valueOf(headers.getBoolean(key)));
                        break;
                    case Number:
                        response.addHeader(key, String.valueOf(headers.getDouble(key)));
                        break;
                    case String:
                        response.addHeader(key, headers.getString(key));
                        break;
                    default:
                        Log.d(TAG, "Could not convert with key: " + key + ".");
                        break;
                }
            }
        }
        responses.put(requestId, response);
    }

    private WritableMap getHeaders(IHTTPSession session) {
        WritableMap headers = Arguments.createMap();
        for (Map.Entry<String,String> entry : session.getHeaders().entrySet()) {
            headers.putString(entry.getKey(), entry.getValue());
        }
        return headers;
    }

    private WritableMap fillRequestMap(IHTTPSession session, String requestId) throws Exception {
        Method method = session.getMethod();
        WritableMap request = Arguments.createMap();

        request.putString("url", session.getUri());
        request.putString("type", method.name());
        request.putMap("headers", getHeaders(session));
        request.putString("requestId", requestId);
        
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        if (files.size() > 0) {
          request.putString("postData", files.get("postData"));
        }

        return request;
    }

    private void sendEvent(ReactContext reactContext, String eventName, @Nullable WritableMap params) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, params);
    }
}
