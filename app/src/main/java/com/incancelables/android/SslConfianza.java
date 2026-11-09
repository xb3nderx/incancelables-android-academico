package com.incancelables.android;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Confianza HTTPS de la app.
 *
 * Android 6.0.1 (API 23) no trae las raíces ISRG en su almacén de CAs,
 * por lo que el backend de Railway falla con
 * "Trust anchor for certification path not found".
 *
 * Esta clase conserva intacto el TrustManager del sistema y agrega
 * únicamente las raíces públicas ISRG Root X1 e ISRG Root X2 como
 * anclas extra. La cadena del servidor sigue validándose con
 * CertPathValidator y la verificación del hostname no se toca
 * (no se modifica ningún HostnameVerifier).
 */
public final class SslConfianza {

    private static final String TAG = "IncancelablesApi";

    private static final int[] RAICES = {
            R.raw.isrg_root_x1,
            R.raw.isrg_root_x2
    };

    private static SSLSocketFactory fabrica = null;
    private static boolean inicializado = false;

    private SslConfianza() {
    }

    /** Idempotente. Debe llamarse desde el hilo principal antes de cargar. */
    public static synchronized void inicializar(Context contexto) {
        if (inicializado) {
            return;
        }
        inicializado = true;

        try {
            KeyStore anclas = KeyStore.getInstance(KeyStore.getDefaultType());
            anclas.load(null, null);

            CertificateFactory fabricaCertificados = CertificateFactory.getInstance("X.509");
            int indice = 0;
            for (int recurso : RAICES) {
                InputStream entrada = contexto.getResources().openRawResource(recurso);
                try {
                    X509Certificate certificado =
                            (X509Certificate) fabricaCertificados.generateCertificate(entrada);
                    anclas.setCertificateEntry("isrg_raiz_" + indice, certificado);
                    indice++;
                } finally {
                    entrada.close();
                }
            }

            TrustManagerFactory gestorAdicional =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            gestorAdicional.init(anclas);
            X509TrustManager adicional = primerX509(gestorAdicional);

            TrustManagerFactory gestorSistema =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            gestorSistema.init((KeyStore) null);
            X509TrustManager sistema = primerX509(gestorSistema);

            SSLContext contextoSsl = SSLContext.getInstance("TLS");
            contextoSsl.init(null, new TrustManager[]{ new Compuesto(sistema, adicional) }, null);
            fabrica = contextoSsl.getSocketFactory();

            Log.i(TAG, "Raíces ISRG cargadas: " + indice);
        } catch (Exception e) {
            fabrica = null;
            Log.e(TAG, "No se pudieron cargar las raíces ISRG", e);
        }
    }

    /** Devuelve null si la inicialización falló: en ese caso se usa el del sistema. */
    public static SSLSocketFactory obtener() {
        return fabrica;
    }

    private static X509TrustManager primerX509(TrustManagerFactory gestor) {
        for (TrustManager manager : gestor.getTrustManagers()) {
            if (manager instanceof X509TrustManager) {
                return (X509TrustManager) manager;
            }
        }
        throw new IllegalStateException("Sin X509TrustManager disponible");
    }

    /**
     * Valida primero con el almacén del sistema y, solo si ese rechaza la
     * cadena, vuelve a validar con las raíces ISRG embebidas. Si tampoco
     * pasa, propaga la excepción original: nunca se acepta una cadena
     * que no validó.
     */
    private static final class Compuesto implements X509TrustManager {

        private final X509TrustManager sistema;
        private final X509TrustManager adicional;

        Compuesto(X509TrustManager sistema, X509TrustManager adicional) {
            this.sistema = sistema;
            this.adicional = adicional;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] cadena, String authType)
                throws CertificateException {
            sistema.checkClientTrusted(cadena, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] cadena, String authType)
                throws CertificateException {
            try {
                sistema.checkServerTrusted(cadena, authType);
                return;
            } catch (CertificateException original) {
                try {
                    adicional.checkServerTrusted(cadena, authType);
                } catch (CertificateException extra) {
                    original.addSuppressed(extra);
                    throw original;
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> emisores = new ArrayList<>();
            for (X509Certificate certificado : sistema.getAcceptedIssuers()) {
                emisores.add(certificado);
            }
            for (X509Certificate certificado : adicional.getAcceptedIssuers()) {
                emisores.add(certificado);
            }
            return emisores.toArray(new X509Certificate[0]);
        }
    }
}
