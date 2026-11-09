package com.incancelables.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/** Constructor de solicitudes JSON del checkout. No realiza I/O. */
public final class CheckoutRequest {

    public enum Operacion {
        CREAR_PEDIDO(true),
        REENVIAR_EMAIL(true),
        VALIDAR_EMAIL(true),
        VALIDAR_CODIGO(true),
        REENVIAR_CODIGO(true),
        OBTENER_PEDIDOS_VERIFICADOS(false),
        REEMPLAZAR_PEDIDO(true),
        CANCELAR_PEDIDO(true),
        INICIAR_PAGO(true),
        ACEPTAR_CORRECCION(true);

        private final boolean mutacion;

        Operacion(boolean mutacion) {
            this.mutacion = mutacion;
        }

        public boolean puedeModificarEstado() {
            return mutacion;
        }
    }

    private static final Pattern EMAIL_BASICO =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final Pattern CODIGO_SEIS_DIGITOS = Pattern.compile("^\\d{6}$");

    private final Operacion operacion;
    private final String ruta;
    private final String cuerpoJson;

    private CheckoutRequest(Operacion operacion, String ruta, JSONObject cuerpo) {
        this.operacion = operacion;
        this.ruta = ruta;
        this.cuerpoJson = cuerpo.toString();
    }

    public static CheckoutRequest crearPedido(String email, String nombre, String apellido,
                                               List<Item> items) {
        String emailNormalizado = textoObligatorio(email, "email");
        if (!EMAIL_BASICO.matcher(emailNormalizado).matches()) {
            throw new IllegalArgumentException("Email inválido");
        }
        String nombreNormalizado = nombreNormalizado(nombre, "nombre");
        String apellidoNormalizado = nombreNormalizado(apellido, "apellido");

        JSONObject cuerpo = new JSONObject();
        poner(cuerpo, "email", emailNormalizado);
        poner(cuerpo, "nombre", nombreNormalizado);
        poner(cuerpo, "apellido", apellidoNormalizado);
        poner(cuerpo, "items", serializarItems(items));
        poner(cuerpo, "canal", "android");
        return new CheckoutRequest(Operacion.CREAR_PEDIDO, "/pedidos", cuerpo);
    }

    public static CheckoutRequest reenviarEmail(int pedidoId) {
        validarPedidoId(pedidoId);
        JSONObject cuerpo = new JSONObject();
        poner(cuerpo, "pedido_id", pedidoId);
        return new CheckoutRequest(Operacion.REENVIAR_EMAIL,
                "/email-verificaciones/reenvio", cuerpo);
    }

    public static CheckoutRequest validarCodigo(int pedidoId, String codigo) {
        validarPedidoId(pedidoId);
        String codigoNormalizado = textoObligatorio(codigo, "codigo");
        if (!CODIGO_SEIS_DIGITOS.matcher(codigoNormalizado).matches()) {
            throw new IllegalArgumentException("codigo inválido");
        }
        JSONObject cuerpo = new JSONObject();
        poner(cuerpo, "pedido_id", pedidoId);
        poner(cuerpo, "codigo", codigoNormalizado);
        return new CheckoutRequest(Operacion.VALIDAR_CODIGO,
                "/email-verificaciones/validar-codigo", cuerpo);
    }

    public static CheckoutRequest reenviarCodigo(int pedidoId) {
        validarPedidoId(pedidoId);
        JSONObject cuerpo = new JSONObject();
        poner(cuerpo, "pedido_id", pedidoId);
        return new CheckoutRequest(Operacion.REENVIAR_CODIGO,
                "/email-verificaciones/codigo", cuerpo);
    }

    public static CheckoutRequest validarEmail(String token) {
        return conToken(Operacion.VALIDAR_EMAIL,
                "/email-verificaciones/validar", token);
    }

    public static CheckoutRequest obtenerPedidosVerificados(String token) {
        return conToken(Operacion.OBTENER_PEDIDOS_VERIFICADOS,
                "/email-verificaciones/pedidos", token);
    }

    public static CheckoutRequest reemplazarPedido(String token, List<Item> items) {
        JSONObject cuerpo = cuerpoTokenItems(token, items);
        return new CheckoutRequest(Operacion.REEMPLAZAR_PEDIDO,
                "/email-verificaciones/pedidos/reemplazo", cuerpo);
    }

    public static CheckoutRequest cancelarPedido(int pedidoId, String token) {
        validarPedidoId(pedidoId);
        JSONObject cuerpo = cuerpoToken(token);
        return new CheckoutRequest(Operacion.CANCELAR_PEDIDO,
                "/pedidos/" + pedidoId + "/cancelar", cuerpo);
    }

    /**
     * simulado puede ser null (backend: APROBADO por defecto), APROBADO o
     * RECHAZADO. Esta clase nunca envía solicitudes por sí misma.
     */
    public static CheckoutRequest iniciarPago(int pedidoId, String token,
                                               List<Item> items, String simulado) {
        validarPedidoId(pedidoId);
        JSONObject cuerpo = cuerpoTokenItems(token, items);
        poner(cuerpo, "accion", "iniciar_pago");
        poner(cuerpo, "proveedor_id", "DUMMY");
        if (simulado != null) {
            if (!"APROBADO".equals(simulado) && !"RECHAZADO".equals(simulado)) {
                throw new IllegalArgumentException("simulacion inválida");
            }
            poner(cuerpo, "simulacion", simulado);
        }
        return new CheckoutRequest(Operacion.INICIAR_PAGO,
                "/pedidos/" + pedidoId + "/pago", cuerpo);
    }

    /** Contrato alternativo que el endpoint de pago también acepta. */
    public static CheckoutRequest aceptarCorreccion(int pedidoId, String token,
                                                     List<Item> items) {
        validarPedidoId(pedidoId);
        JSONObject cuerpo = cuerpoTokenItems(token, items);
        poner(cuerpo, "accion", "aceptar_correccion");
        return new CheckoutRequest(Operacion.ACEPTAR_CORRECCION,
                "/pedidos/" + pedidoId + "/pago", cuerpo);
    }

    public Operacion getOperacion() {
        return operacion;
    }

    public String getRuta() {
        return ruta;
    }

    public String getCuerpoJson() {
        return cuerpoJson;
    }

    private static CheckoutRequest conToken(Operacion operacion, String ruta, String token) {
        return new CheckoutRequest(operacion, ruta, cuerpoToken(token));
    }

    private static JSONObject cuerpoToken(String token) {
        JSONObject cuerpo = new JSONObject();
        poner(cuerpo, "token", tokenObligatorio(token));
        return cuerpo;
    }

    private static JSONObject cuerpoTokenItems(String token, List<Item> items) {
        JSONObject cuerpo = cuerpoToken(token);
        poner(cuerpo, "items", serializarItems(items));
        return cuerpo;
    }

    private static JSONArray serializarItems(List<Item> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("El carrito está vacío");
        }
        JSONArray arreglo = new JSONArray();
        for (Item item : items) {
            if (item == null || item.productoId <= 0 || item.cantidad <= 0
                    || Double.isNaN(item.precioUnitario)
                    || Double.isInfinite(item.precioUnitario)) {
                throw new IllegalArgumentException("Ítem de carrito inválido");
            }
            JSONObject linea = new JSONObject();
            poner(linea, "producto_id", item.productoId);
            poner(linea, "cantidad", item.cantidad);
            poner(linea, "precio_unitario", item.precioUnitario);
            arreglo.put(linea);
        }
        return arreglo;
    }

    private static String nombreNormalizado(String valor, String campo) {
        String normalizado = textoObligatorio(valor, campo);
        int longitud = normalizado.codePointCount(0, normalizado.length());
        if (longitud > 100) {
            throw new IllegalArgumentException(campo + " inválido");
        }
        return normalizado;
    }

    private static String textoObligatorio(String valor, String campo) {
        if (valor == null || valor.trim().isEmpty()) {
            throw new IllegalArgumentException(campo + " inválido");
        }
        return valor.trim();
    }

    private static String tokenObligatorio(String token) {
        String normalizado = textoObligatorio(token, "token");
        if (normalizado.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw new IllegalArgumentException("token inválido");
        }
        return normalizado;
    }

    private static void validarPedidoId(int pedidoId) {
        if (pedidoId <= 0) {
            throw new IllegalArgumentException("pedido_id inválido");
        }
    }

    private static void poner(JSONObject objeto, String clave, Object valor) {
        try {
            objeto.put(clave, valor);
        } catch (JSONException e) {
            throw new IllegalArgumentException("No se pudo construir JSON", e);
        }
    }

    public static final class Item {
        private final int productoId;
        private final int cantidad;
        private final double precioUnitario;

        public Item(int productoId, int cantidad, double precioUnitario) {
            this.productoId = productoId;
            this.cantidad = cantidad;
            this.precioUnitario = precioUnitario;
        }

        public int getProductoId() {
            return productoId;
        }

        public int getCantidad() {
            return cantidad;
        }

        public double getPrecioUnitario() {
            return precioUnitario;
        }
    }
}
