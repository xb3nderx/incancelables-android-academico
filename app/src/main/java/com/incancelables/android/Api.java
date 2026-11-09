package com.incancelables.android;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * Acceso a la API académica por HTTPS con HttpURLConnection.
 *
 * La clase conserva el último catálogo en memoria y deduplica las
 * solicitudes normales en vuelo. Un refresco explícito durante un GET
 * deja una sola lectura posterior para no conservar datos previos a una
 * mutación. El compromiso del pedido propio se suma solo a la presentación.
 */
public final class Api {

    private static final String TAG = "IncancelablesApi";

    public static final String URL_BASE =
            "https://incancelables-ecommerce-academico-production.up.railway.app/api";
    public static final String URL_PRODUCTOS = URL_BASE + "/productos";

    public static final String ERROR_TIMEOUT = "timeout";
    public static final String ERROR_RED = "red";

    private static final int TIMEOUT_CONEXION_MS = 10000;
    private static final int TIMEOUT_LECTURA_MS = 15000;

    public interface Oyente {
        void onProductos(List<Producto> productos);

        void onError(String codigoError);
    }

    private static final ExecutorService EJECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler HILO_PRINCIPAL = new Handler(Looper.getMainLooper());
    private static final List<Oyente> OYENTES = new CopyOnWriteArrayList<>();

    private static volatile boolean cargando = false;
    private static boolean refrescoPendiente = false;
    private static volatile List<Producto> productosGuardados = null;
    private static volatile String errorGuardado = null;
    private static volatile Map<Integer, Integer> compromisoDelPedido = Collections.emptyMap();

    private Api() {
    }

    public static void registrar(Oyente oyente) {
        if (oyente != null && !OYENTES.contains(oyente)) {
            OYENTES.add(oyente);
        }
    }

    public static void desregistrar(Oyente oyente) {
        OYENTES.remove(oyente);
    }

    public static boolean estaCargando() {
        return cargando;
    }

    public static synchronized List<Producto> getProductosGuardados() {
        return aplicarCompromisoPedido(productosGuardados);
    }

    public static String getErrorGuardado() {
        return errorGuardado;
    }

    /**
     * Lanza la descarga si no hay una en curso. Los oyentes activos son
     * notificados al terminar, en el hilo principal.
     */
    public static synchronized void cargar() {
        if (cargando) {
            return;
        }
        iniciarCarga();
    }

    /**
     * Solicita una lectura nueva. Si ya hay un GET en curso, deja pendiente
     * una única lectura posterior para que el resultado anterior no deje la
     * caché obsoleta respecto de una mutación ocurrida durante ese GET.
     */
    public static synchronized void refrescar() {
        if (cargando) {
            refrescoPendiente = true;
            return;
        }
        iniciarCarga();
    }

    private static void iniciarCarga() {
        cargando = true;
        errorGuardado = null;
        EJECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                ejecutarCarga();
            }
        });
    }

    /** Define las unidades del pedido propio ya descontadas por la API. */
    public static synchronized void definirCompromisoPedido(
            List<CheckoutRequest.Item> items) {
        Map<Integer, Integer> cantidades = new HashMap<>();
        if (items != null) {
            for (CheckoutRequest.Item item : items) {
                if (item == null || item.getProductoId() <= 0 || item.getCantidad() <= 0) {
                    continue;
                }
                int actual = cantidades.containsKey(item.getProductoId())
                        ? cantidades.get(item.getProductoId()) : 0;
                cantidades.put(item.getProductoId(), actual + item.getCantidad());
            }
        }
        compromisoDelPedido = Collections.unmodifiableMap(cantidades);
        sincronizarCarritoConCache();
    }

    public static synchronized void limpiarCompromisoPedido() {
        compromisoDelPedido = Collections.emptyMap();
        sincronizarCarritoConCache();
    }

    private static void sincronizarCarritoConCache() {
        List<Producto> productos = aplicarCompromisoPedido(productosGuardados);
        if (productos != null) {
            Carrito.obtenerInstancia().sincronizarProductos(productos);
        }
    }

    static List<Producto> aplicarCompromisoPedido(List<Producto> productos) {
        if (productos == null) return null;
        Map<Integer, Integer> compromiso = compromisoDelPedido;
        List<Producto> ajustados = new ArrayList<>(productos.size());
        for (Producto producto : productos) {
            if (producto == null) continue;
            int unidades = compromiso.containsKey(producto.getId())
                    ? compromiso.get(producto.getId()) : 0;
            long disponibilidad = (long) producto.getDisponibilidad() + unidades;
            int base = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, disponibilidad));
            ajustados.add(unidades == 0 ? producto : new Producto(producto.getId(),
                    producto.getNombre(), producto.getDescripcion(), producto.getPrecio(), base,
                    producto.getImagenes()));
        }
        return ajustados;
    }

    private static void ejecutarCarga() {
        List<Producto> lista = null;
        String codigoError = null;
        try {
            lista = descargarProductos();
        } catch (SocketTimeoutException e) {
            codigoError = ERROR_TIMEOUT;
            Log.e(TAG, "GET " + URL_PRODUCTOS + " -> timeout", e);
        } catch (Exception e) {
            codigoError = ERROR_RED;
            Log.e(TAG, "GET " + URL_PRODUCTOS + " -> error", e);
        }

        synchronized (Api.class) {
            if (refrescoPendiente) {
                refrescoPendiente = false;
                iniciarCarga();
                return;
            }
            if (codigoError == null) {
                productosGuardados = lista;
            } else {
                errorGuardado = codigoError;
            }
            cargando = false;
        }

        final String errorFinal = codigoError;
        final List<Producto> listaFinal = lista;
        final List<Producto> productosPresentados = codigoError == null
                ? aplicarCompromisoPedido(listaFinal) : null;
        if (productosPresentados != null) {
            Carrito.obtenerInstancia().sincronizarProductos(productosPresentados);
        }
        HILO_PRINCIPAL.post(new Runnable() {
            @Override
            public void run() {
                for (Oyente oyente : OYENTES) {
                    if (errorFinal == null) {
                        oyente.onProductos(productosPresentados);
                    } else {
                        oyente.onError(errorFinal);
                    }
                }
            }
        });
    }

    private static List<Producto> descargarProductos() throws Exception {
        HttpURLConnection conexion = (HttpURLConnection) new URL(URL_PRODUCTOS).openConnection();
        try {
            conexion.setConnectTimeout(TIMEOUT_CONEXION_MS);
            conexion.setReadTimeout(TIMEOUT_LECTURA_MS);
            conexion.setRequestMethod("GET");
            conexion.setInstanceFollowRedirects(true);
            conexion.setRequestProperty("Accept", "application/json");

            // Solo se cambia el set de anclas de confianza. El
            // HostnameVerifier por defecto sigue intacto.
            if (conexion instanceof HttpsURLConnection) {
                SSLSocketFactory fabrica = SslConfianza.obtener();
                if (fabrica != null) {
                    ((HttpsURLConnection) conexion).setSSLSocketFactory(fabrica);
                } else {
                    Log.e(TAG, "SSLSocketFactory de ISRG no disponible; se usa el del sistema");
                }
            }

            int codigo = conexion.getResponseCode();
            if (codigo < 200 || codigo >= 300) {
                throw new IOException("HTTP " + codigo);
            }

            JSONArray cuerpo = new JSONArray(leerFlujo(conexion.getInputStream()));
            List<Producto> productos = new ArrayList<>(cuerpo.length());
            for (int i = 0; i < cuerpo.length(); i++) {
                productos.add(Producto.desdeJson(cuerpo.getJSONObject(i)));
            }
            return productos;
        } finally {
            conexion.disconnect();
        }
    }

    private static String leerFlujo(InputStream flujo) throws IOException {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int leido;
        while ((leido = flujo.read(buffer)) != -1) {
            salida.write(buffer, 0, leido);
        }
        return salida.toString("UTF-8");
    }
}
