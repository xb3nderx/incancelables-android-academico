package com.incancelables.android;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/** Transporte asíncrono del checkout. Nunca reintenta automáticamente un POST. */
public final class CheckoutApi implements AutoCloseable, CheckoutSender {

    interface Transporte {
        RespuestaHttp ejecutar(CheckoutRequest solicitud) throws IOException;
    }

    static final class RespuestaHttp {
        final int codigo;
        final String cuerpo;

        RespuestaHttp(int codigo, String cuerpo) {
            this.codigo = codigo;
            this.cuerpo = cuerpo;
        }
    }

    private static final int TIMEOUT_CONEXION_MS = 10000;
    private static final int TIMEOUT_LECTURA_MS = 15000;

    private final Transporte transporte;
    private final Executor trabajo;
    private final Executor principal;
    private final ExecutorService ejecutorPropio;

    /**
     * Cliente de producción. Debe crearse desde una Activity en el hilo
     * principal para inicializar las raíces HTTPS existentes.
     */
    public CheckoutApi(Context contexto) {
        this(new TransporteHttp(), Executors.newSingleThreadExecutor(),
                ejecutorPrincipal(), true);
        if (contexto == null) {
            throw new IllegalArgumentException("Context requerido");
        }
        SslConfianza.inicializar(contexto.getApplicationContext());
    }

    /** Constructor inyectable para pruebas locales con respuestas simuladas. */
    CheckoutApi(Transporte transporte, Executor trabajo, Executor principal) {
        this(transporte, trabajo, principal, false);
    }

    private CheckoutApi(Transporte transporte, Executor trabajo, Executor principal,
                        boolean propietarioDelEjecutor) {
        if (transporte == null || trabajo == null || principal == null) {
            throw new IllegalArgumentException("Dependencias del cliente checkout inválidas");
        }
        this.transporte = transporte;
        this.trabajo = trabajo;
        this.principal = principal;
        this.ejecutorPropio = propietarioDelEjecutor && trabajo instanceof ExecutorService
                ? (ExecutorService) trabajo : null;
    }

    @Override
    public void enviar(final CheckoutRequest solicitud, final CheckoutSender.Callback callback) {
        if (solicitud == null || callback == null) {
            throw new IllegalArgumentException("Solicitud y callback son obligatorios");
        }
        trabajo.execute(new Runnable() {
            @Override
            public void run() {
                final CheckoutResponse respuesta = ejecutarAhora(solicitud);
                principal.execute(new Runnable() {
                    @Override
                    public void run() {
                        callback.alCompletar(respuesta);
                    }
                });
            }
        });
    }

    /** Ejecución directa para pruebas inyectadas; production UI debe usar enviar(). */
    CheckoutResponse ejecutarAhora(CheckoutRequest solicitud) {
        try {
            RespuestaHttp respuesta = transporte.ejecutar(solicitud);
            return CheckoutResponse.interpretar(solicitud, respuesta.codigo, respuesta.cuerpo);
        } catch (SocketTimeoutException e) {
            return CheckoutResponse.errorDeRed(solicitud);
        } catch (IOException e) {
            return CheckoutResponse.errorDeRed(solicitud);
        }
    }

    @Override
    public void close() {
        if (ejecutorPropio != null) {
            ejecutorPropio.shutdown();
        }
    }

    private static Executor ejecutorPrincipal() {
        final Handler handler = new Handler(Looper.getMainLooper());
        return new Executor() {
            @Override
            public void execute(Runnable comando) {
                handler.post(comando);
            }
        };
    }

    private static final class TransporteHttp implements Transporte {
        @Override
        public RespuestaHttp ejecutar(CheckoutRequest solicitud) throws IOException {
            HttpURLConnection conexion = null;
            try {
                URL url = new URL(Api.URL_BASE + solicitud.getRuta());
                conexion = (HttpURLConnection) url.openConnection();
                conexion.setConnectTimeout(TIMEOUT_CONEXION_MS);
                conexion.setReadTimeout(TIMEOUT_LECTURA_MS);
                conexion.setRequestMethod("POST");
                conexion.setInstanceFollowRedirects(false);
                conexion.setRequestProperty("Accept", "application/json");
                conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conexion.setDoOutput(true);

                // Reutiliza las anclas ISRG de la app; la verificación del host
                // predeterminada de HttpsURLConnection permanece intacta.
                if (conexion instanceof HttpsURLConnection) {
                    SSLSocketFactory fabrica = SslConfianza.obtener();
                    if (fabrica != null) {
                        ((HttpsURLConnection) conexion).setSSLSocketFactory(fabrica);
                    }
                }

                byte[] cuerpo = solicitud.getCuerpoJson().getBytes("UTF-8");
                conexion.setFixedLengthStreamingMode(cuerpo.length);
                try (OutputStream salida = conexion.getOutputStream()) {
                    salida.write(cuerpo);
                    salida.flush();
                }

                int codigo = conexion.getResponseCode();
                InputStream flujo = codigo >= 400
                        ? conexion.getErrorStream()
                        : conexion.getInputStream();
                return new RespuestaHttp(codigo, flujo == null ? "" : leerFlujo(flujo));
            } finally {
                if (conexion != null) {
                    conexion.disconnect();
                }
            }
        }

        private static String leerFlujo(InputStream flujo) throws IOException {
            try (InputStream entrada = flujo;
                 ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int leido;
                while ((leido = entrada.read(buffer)) != -1) {
                    salida.write(buffer, 0, leido);
                }
                return salida.toString("UTF-8");
            }
        }
    }
}
