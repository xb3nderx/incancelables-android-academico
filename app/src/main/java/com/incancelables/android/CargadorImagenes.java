package com.incancelables.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/** Carga HTTPS de imágenes con caché LRU y deduplicación de descargas en vuelo. */
public final class CargadorImagenes {

    interface Descargador {
        Bitmap descargar(String url) throws IOException;
    }

    static final int MAX_DIMENSION_PIXELES = 640;
    private static final int MAX_BYTES_IMAGEN = 6 * 1024 * 1024;
    private static final int MAX_REDIRECCIONES = 4;
    private static final long REINTENTO_FALLO_MS = 30_000L;
    private static final int TIMEOUT_CONEXION_MS = 8_000;
    private static final int TIMEOUT_LECTURA_MS = 12_000;

    private static volatile CargadorImagenes instancia;

    private final LruCache<String, Bitmap> cache;
    private final LruCache<String, Long> fallosRecientes = new LruCache<>(64);
    private final Map<String, List<WeakReference<ImageView>>> solicitudes = new HashMap<>();
    private final ThreadPoolExecutor ejecutor;
    private final Handler hiloPrincipal = new Handler(Looper.getMainLooper());
    private final Descargador descargador;

    private CargadorImagenes(Context contexto) {
        this(contexto, null);
    }

    CargadorImagenes(Context contexto, Descargador descargadorDePrueba) {
        SslConfianza.inicializar(contexto.getApplicationContext());
        descargador = descargadorDePrueba == null ? this::descargar : descargadorDePrueba;
        int memoriaMaximaKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        int cacheMaximaKb = Math.min(20 * 1024, Math.max(2 * 1024, memoriaMaximaKb / 10));
        cache = new LruCache<String, Bitmap>(cacheMaximaKb) {
            @Override
            protected int sizeOf(String clave, Bitmap bitmap) {
                return Math.max(1, bitmap.getByteCount() / 1024);
            }
        };
        ejecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(16), new ThreadFactory() {
            private int numero;

            @Override
            public synchronized Thread newThread(Runnable tarea) {
                Thread hilo = new Thread(tarea, "incancelables-imagen-" + (++numero));
                hilo.setDaemon(true);
                return hilo;
            }
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    public static CargadorImagenes obtener(Context contexto) {
        CargadorImagenes actual = instancia;
        if (actual == null) {
            synchronized (CargadorImagenes.class) {
                actual = instancia;
                if (actual == null) {
                    actual = new CargadorImagenes(contexto);
                    instancia = actual;
                }
            }
        }
        return actual;
    }

    /** Asigna primero el placeholder; una respuesta tardía solo afecta el URL aún ligado a la vista. */
    public void cargar(ImageView vista, String url, String textoAlternativo) {
        if (vista == null) return;
        Object urlAnterior = vista.getTag();
        if (urlAnterior instanceof String && !urlAnterior.equals(url)) {
            quitarSuscriptor((String) urlAnterior, vista);
        }
        vista.setTag(url);
        vista.setContentDescription(textoAlternativo == null || textoAlternativo.trim().isEmpty()
                ? vista.getContext().getString(R.string.imagen_producto_descripcion)
                : textoAlternativo);

        if (!urlHttpsValida(url)) {
            mostrarPlaceholder(vista);
            return;
        }

        Bitmap guardada = cache.get(url);
        if (guardada != null && !guardada.isRecycled()) {
            mostrar(vista, url, guardada);
            return;
        }
        mostrarPlaceholder(vista);

        boolean esperarSolicitudExistente = false;
        synchronized (solicitudes) {
            guardada = cache.get(url);
            if (guardada != null && !guardada.isRecycled()) {
                // Otra descarga pudo terminar entre el primer lookup y este lock.
            } else {
                Long reintentarTras = fallosRecientes.get(url);
                if (reintentarTras != null
                        && SystemClock.elapsedRealtime() < reintentarTras) {
                    return;
                }
                fallosRecientes.remove(url);
                List<WeakReference<ImageView>> vistas = solicitudes.get(url);
                if (vistas != null) {
                    agregarVistaUnica(vistas, vista);
                    esperarSolicitudExistente = true;
                } else {
                    vistas = new ArrayList<>();
                    vistas.add(new WeakReference<>(vista));
                    solicitudes.put(url, vistas);
                }
            }
        }
        if (guardada != null && !guardada.isRecycled()) {
            mostrar(vista, url, guardada);
            return;
        }
        if (esperarSolicitudExistente) return;

        try {
            ejecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (!haySuscriptores(url)) return;
                    Bitmap bitmap = null;
                    try {
                        bitmap = descargador.descargar(url);
                    } catch (IOException | RuntimeException ignorado) {
                        // La vista conserva el recurso placeholder; no se expone el URL ni el fallo.
                    }
                    terminarSolicitud(url, bitmap);
                }
            });
        } catch (RejectedExecutionException ignorado) {
            terminarSolicitud(url, null, false);
        }
    }

    static boolean urlHttpsValida(String valor) {
        if (valor == null || valor.trim().isEmpty()) return false;
        try {
            URL url = new URL(valor);
            return "https".equalsIgnoreCase(url.getProtocol())
                    && url.getHost() != null && !url.getHost().isEmpty();
        } catch (Exception ignorado) {
            return false;
        }
    }

    static int calcularMuestra(int ancho, int alto, int maxDimension) {
        if (ancho <= 0 || alto <= 0 || maxDimension <= 0) return 1;
        int muestra = 1;
        while (Math.max(ancho / muestra, alto / muestra) > maxDimension
                && muestra <= (1 << 29)) {
            muestra *= 2;
        }
        return muestra;
    }

    private static void agregarVistaUnica(List<WeakReference<ImageView>> vistas,
                                          ImageView nueva) {
        for (int i = vistas.size() - 1; i >= 0; i--) {
            ImageView existente = vistas.get(i).get();
            if (existente == null) {
                vistas.remove(i);
            } else if (existente == nueva) {
                return;
            }
        }
        vistas.add(new WeakReference<>(nueva));
    }

    private void quitarSuscriptor(String url, ImageView vista) {
        synchronized (solicitudes) {
            List<WeakReference<ImageView>> vistas = solicitudes.get(url);
            if (vistas == null) return;
            for (int i = vistas.size() - 1; i >= 0; i--) {
                ImageView existente = vistas.get(i).get();
                if (existente == null || existente == vista) vistas.remove(i);
            }
        }
    }

    private boolean haySuscriptores(String url) {
        synchronized (solicitudes) {
            List<WeakReference<ImageView>> vistas = solicitudes.get(url);
            if (vistas == null) return false;
            for (int i = vistas.size() - 1; i >= 0; i--) {
                if (vistas.get(i).get() == null) vistas.remove(i);
            }
            if (vistas.isEmpty()) {
                solicitudes.remove(url);
                return false;
            }
            return true;
        }
    }

    private Bitmap descargar(String valorUrl) throws IOException {
        URL actual = new URL(valorUrl);
        for (int redireccion = 0; redireccion <= MAX_REDIRECCIONES; redireccion++) {
            if (!urlHttpsValida(actual.toExternalForm())) {
                throw new IOException("Se requiere HTTPS");
            }

            HttpsURLConnection conexion = (HttpsURLConnection) actual.openConnection();
            try {
                conexion.setConnectTimeout(TIMEOUT_CONEXION_MS);
                conexion.setReadTimeout(TIMEOUT_LECTURA_MS);
                conexion.setRequestMethod("GET");
                conexion.setInstanceFollowRedirects(false);
                conexion.setRequestProperty("Accept", "image/*");
                SSLSocketFactory fabrica = SslConfianza.obtener();
                if (fabrica != null) conexion.setSSLSocketFactory(fabrica);

                int codigo = conexion.getResponseCode();
                if (codigo >= 300 && codigo < 400) {
                    String ubicacion = conexion.getHeaderField("Location");
                    if (ubicacion == null || redireccion == MAX_REDIRECCIONES) {
                        throw new IOException("Redirección de imagen inválida");
                    }
                    URL siguiente = new URL(actual, ubicacion);
                    if (!urlHttpsValida(siguiente.toExternalForm())) {
                        throw new IOException("Redirección fuera de HTTPS");
                    }
                    actual = siguiente;
                    continue;
                }
                if (codigo != HttpURLConnection.HTTP_OK) {
                    throw new IOException("HTTP " + codigo);
                }
                int largo = conexion.getContentLength();
                if (largo > MAX_BYTES_IMAGEN) throw new IOException("Imagen demasiado grande");
                byte[] bytes = leerLimitado(conexion.getInputStream());
                return decodificar(bytes);
            } finally {
                conexion.disconnect();
            }
        }
        throw new IOException("Demasiadas redirecciones");
    }

    private static byte[] leerLimitado(InputStream entrada) throws IOException {
        try {
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int leido;
            int total = 0;
            while ((leido = entrada.read(buffer)) != -1) {
                total += leido;
                if (total > MAX_BYTES_IMAGEN) throw new IOException("Imagen demasiado grande");
                salida.write(buffer, 0, leido);
            }
            return salida.toByteArray();
        } finally {
            entrada.close();
        }
    }

    private static Bitmap decodificar(byte[] bytes) {
        BitmapFactory.Options limites = new BitmapFactory.Options();
        limites.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, limites);
        if (limites.outWidth <= 0 || limites.outHeight <= 0) return null;

        BitmapFactory.Options opciones = new BitmapFactory.Options();
        opciones.inSampleSize = calcularMuestra(limites.outWidth, limites.outHeight,
                MAX_DIMENSION_PIXELES);
        opciones.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opciones);
    }

    private void terminarSolicitud(String url, Bitmap bitmap) {
        terminarSolicitud(url, bitmap, true);
    }

    private void terminarSolicitud(String url, Bitmap bitmap, boolean registrarFallo) {
        List<WeakReference<ImageView>> vistas;
        synchronized (solicitudes) {
            vistas = solicitudes.remove(url);
            if (bitmap != null) {
                cache.put(url, bitmap);
                fallosRecientes.remove(url);
            } else if (registrarFallo) {
                fallosRecientes.put(url, SystemClock.elapsedRealtime() + REINTENTO_FALLO_MS);
            }
        }
        if (vistas == null) return;
        Bitmap resultado = bitmap;
        hiloPrincipal.post(new Runnable() {
            @Override
            public void run() {
                for (WeakReference<ImageView> referencia : vistas) {
                    ImageView vista = referencia.get();
                    if (vista != null && url.equals(vista.getTag()) && resultado != null) {
                        mostrar(vista, url, resultado);
                    }
                }
            }
        });
    }

    private static void mostrar(ImageView vista, String url, Bitmap bitmap) {
        if (!url.equals(vista.getTag())) return;
        vista.setScaleType(ImageView.ScaleType.CENTER_CROP);
        vista.setImageBitmap(bitmap);
    }

    private static void mostrarPlaceholder(ImageView vista) {
        vista.setScaleType(ImageView.ScaleType.CENTER);
        vista.setImageResource(R.drawable.imagen_producto_placeholder);
    }
}
