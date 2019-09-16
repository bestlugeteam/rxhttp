package com.example.httpsender;

import android.app.Application;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import io.reactivex.functions.Function;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import rxhttp.wrapper.param.Param;
import rxhttp.wrapper.param.RxHttp;
import rxhttp.wrapper.ssl.SSLSocketFactoryImpl;
import rxhttp.wrapper.ssl.X509TrustManagerImpl;

/**
 * User: ljx
 * Date: 2019/3/31
 * Time: 09:11
 */
public class AppHolder extends Application {

    private static AppHolder instance;

    public static AppHolder getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initRxHttp();
    }


    private void initRxHttp() {

        X509TrustManager trustAllCert = new X509TrustManagerImpl();
        SSLSocketFactory sslSocketFactory = new SSLSocketFactoryImpl(trustAllCert);
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .sslSocketFactory(sslSocketFactory, trustAllCert) //添加信任证书
            .hostnameVerifier((hostname, session) -> true) //忽略host验证
            .followRedirects(false)  //禁制OkHttp的重定向操作，我们自己处理重定向
            .addInterceptor(new RedirectInterceptor())
            .build();

        //RxHttp初始化，自定义OkHttpClient对象,非必须
        RxHttp.init(client, BuildConfig.DEBUG);
//        RxHttp.setOnConverter(s -> s); //设置数据转换器,可用于数据解密

        Function<Param, Param> onParamAssembly = p -> {
            //根据不同请求添加不同参数，子线程执行，每次发送请求前都会被回调
            //为url 添加前缀或者后缀  并重新设置url
            //p.setUrl("");
            return p.add("versionName", "1.0.0")//添加公共参数
                .addHeader("deviceType", "android"); //添加公共请求头
        };
        RxHttp.setOnParamAssembly(onParamAssembly);//设置公共参数回调
    }

    //处理重定向的拦截器，非必须
    public class RedirectInterceptor implements Interceptor {

        @Override
        public Response intercept(Chain chain) throws IOException {
            okhttp3.Request request = chain.request();
            Response response = chain.proceed(request);
            int code = response.code();
            if (code == 308) {
                //获取重定向的地址
                String location = response.headers().get("Location");
                //重新构建请求
                Request newRequest = request.newBuilder().url(location).build();
                response.close();
                response = chain.proceed(newRequest);
            }
            return response;
        }
    }
}
