package com.incancelables.android;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Interpretación de respuestas del checkout; conserva data sin loguearla. */
public final class CheckoutResponse {

    public enum Estado {
        PEDIDO_CREADO,
        CORRECCION_COMERCIAL,
        EMAIL_REENVIADO,
        COOLDOWN_REENVIO,
        CODIGO_CONFIRMADO,
        CODIGO_REENVIADO,
        TOKEN_CONFIRMADO,
        PEDIDOS_RECUPERADOS,
        PEDIDO_REEMPLAZADO,
        PEDIDO_CANCELADO,
        PAGO_CONFIRMADO,
        PAGO_REQUIERE_REVISION,
        PAGO_RECHAZADO,
        PAGO_EN_PROCESO,
        ERROR_HTTP,
        ERROR_JSON,
        RESPUESTA_INESPERADA,
        ERROR_RED,
        RESULTADO_INCIERTO
    }

    private final Estado estado;
    private final int codigoHttp;
    private final String codigoError;
    private final JSONObject datos;
    private final boolean requiereReconciliacion;

    private CheckoutResponse(Estado estado, int codigoHttp, String codigoError,
                             JSONObject datos, boolean requiereReconciliacion) {
        this.estado = estado;
        this.codigoHttp = codigoHttp;
        this.codigoError = codigoError;
        this.datos = datos;
        this.requiereReconciliacion = requiereReconciliacion;
    }

    public static CheckoutResponse interpretar(CheckoutRequest solicitud,
                                               int codigoHttp, String cuerpo) {
        JSONObject datos;
        try {
            if (cuerpo == null || cuerpo.trim().isEmpty()) {
                throw new JSONException("Respuesta vacía");
            }
            datos = new JSONObject(cuerpo);
        } catch (JSONException e) {
            if (esExitoso(codigoHttp)) {
                return invalida(solicitud, codigoHttp, Estado.ERROR_JSON);
            }
            return new CheckoutResponse(Estado.ERROR_HTTP, codigoHttp, null,
                    null, solicitud.getOperacion().puedeModificarEstado() && codigoHttp >= 500);
        }

        if (codigoHttp < 200 || codigoHttp >= 300) {
            String error = texto(datos, "error");
            boolean reenvioDeCodigo = solicitud.getOperacion() == CheckoutRequest.Operacion.REENVIAR_EMAIL
                    || solicitud.getOperacion() == CheckoutRequest.Operacion.REENVIAR_CODIGO;
            if (reenvioDeCodigo
                    && codigoHttp == 429 && "cooldown_activo".equals(error)) {
                return new CheckoutResponse(Estado.COOLDOWN_REENVIO, codigoHttp,
                        error, datos, false);
            }
            return new CheckoutResponse(Estado.ERROR_HTTP, codigoHttp, error, datos,
                    solicitud.getOperacion().puedeModificarEstado() && codigoHttp >= 500);
        }

        switch (solicitud.getOperacion()) {
            case CREAR_PEDIDO:
                if (codigoHttp == 201 && "creado".equals(texto(datos, "resultado"))
                        && pedidoValido(datos.optJSONObject("pedido"))
                        && datos.has("email_enviado")
                        && datos.opt("email_enviado") instanceof Boolean) {
                    return respuesta(Estado.PEDIDO_CREADO, codigoHttp, datos);
                }
                if (codigoHttp == 200 && "correccion".equals(texto(datos, "resultado"))
                        && esArray(datos, "carrito_corregido")
                        && esArray(datos, "disponibilidad") && esArray(datos, "motivos")) {
                    return respuesta(Estado.CORRECCION_COMERCIAL, codigoHttp, datos);
                }
                return invalida(solicitud, codigoHttp, Estado.RESPUESTA_INESPERADA);

            case REENVIAR_EMAIL:
                if (codigoHttp == 200 && "reenviado".equals(texto(datos, "resultado"))
                        && numeroPositivo(datos.opt("email_verificacion_id"))) {
                    return respuesta(Estado.EMAIL_REENVIADO, codigoHttp, datos);
                }
                if ("verificacion_ya_verificada".equals(texto(datos, "error"))) {
                    return respuesta(Estado.ERROR_HTTP, codigoHttp, datos);
                }
                return invalida(solicitud, codigoHttp, Estado.RESPUESTA_INESPERADA);

            case VALIDAR_EMAIL:
                if (codigoHttp == 200 && "confirmado".equals(texto(datos, "resultado"))
                        && numeroPositivo(datos.opt("email_verificacion_id"))
                        && esArray(datos, "pedidos")) {
                    return respuesta(Estado.TOKEN_CONFIRMADO, codigoHttp, datos);
                }
                return invalida(solicitud, codigoHttp, Estado.RESPUESTA_INESPERADA);

            case VALIDAR_CODIGO:
                if (codigoHttp == 200 && "confirmado".equals(texto(datos, "resultado"))
                        && esToken(texto(datos, "token"))
                        && numeroPositivo(datos.opt("email_verificacion_id"))
                        && esArray(datos, "pedidos")) {
                    return respuesta(Estado.CODIGO_CONFIRMADO, codigoHttp, datos);
                }
                return invalida(solicitud, codigoHttp, Estado.RESPUESTA_INESPERADA);

            case REENVIAR_CODIGO:
                if (codigoHttp == 200 && "codigo_reenviado".equals(texto(datos, "resultado"))
                        && numeroPositivo(datos.opt("email_verificacion_id"))) {
                    return respuesta(Estado.CODIGO_REENVIADO, codigoHttp, datos);
                }
                return invalida(solicitud, codigoHttp, Estado.RESPUESTA_INESPERADA);

            case OBTENER_PEDIDOS_VERIFICADOS:
                if (codigoHttp == 200 && "ok".equals(texto(datos, "resultado"))
                        && esArray(datos, "pedidos")) {
                    return respuesta(Estado.PEDIDOS_RECUPERADOS, codigoHttp, datos);
                }
                return invalida(solicitud, codigoHttp, Estado.RESPUESTA_INESPERADA);

            case REEMPLAZAR_PEDIDO:
                if (codigoHttp == 200 && "reemplazado".equals(texto(datos, "resultado"))
                        && pedidoValido(datos.optJSONObject("pedido"))
                        && pedidoValido(datos.optJSONObject("pedido_anterior"))) {
                    return respuesta(Estado.PEDIDO_REEMPLAZADO, codigoHttp, datos);
                }
                if (codigoHttp == 200 && "correccion".equals(texto(datos, "resultado"))
                        && esArray(datos, "carrito_corregido")
                        && esArray(datos, "disponibilidad") && esArray(datos, "motivos")) {
                    return respuesta(Estado.CORRECCION_COMERCIAL, codigoHttp, datos);
                }
                return invalida(solicitud, codigoHttp, Estado.RESPUESTA_INESPERADA);

            case CANCELAR_PEDIDO:
                if (codigoHttp == 200 && "cancelado".equals(texto(datos, "resultado"))
                        && pedidoValido(datos.optJSONObject("pedido"))
                        && "NO_PAGADO".equals(texto(datos.optJSONObject("pedido"), "estado"))) {
                    return respuesta(Estado.PEDIDO_CANCELADO, codigoHttp, datos);
                }
                return invalida(solicitud, codigoHttp, Estado.RESPUESTA_INESPERADA);

            case INICIAR_PAGO:
            case ACEPTAR_CORRECCION:
                return interpretarPago(solicitud, codigoHttp, datos);

            default:
                return invalida(solicitud, codigoHttp, Estado.RESPUESTA_INESPERADA);
        }
    }

    static CheckoutResponse errorDeRed(CheckoutRequest solicitud) {
        if (solicitud.getOperacion().puedeModificarEstado()) {
            return new CheckoutResponse(Estado.RESULTADO_INCIERTO, -1,
                    "fallo_transporte", null, true);
        }
        return new CheckoutResponse(Estado.ERROR_RED, -1,
                "fallo_transporte", null, false);
    }

    private static CheckoutResponse interpretarPago(CheckoutRequest solicitud,
                                                   int codigoHttp, JSONObject datos) {
        String resultado = texto(datos, "resultado");
        JSONObject pedido = datos.optJSONObject("pedido");
        if ("aprobado".equals(resultado) && pedidoValido(pedido)) {
            String estadoPedido = texto(pedido, "estado");
            if ("PAGADO".equals(estadoPedido)) {
                return respuesta(Estado.PAGO_CONFIRMADO, codigoHttp, datos);
            }
            if ("PAGADO_STOCK_NO_AFECTADO".equals(estadoPedido)) {
                return respuesta(Estado.PAGO_REQUIERE_REVISION, codigoHttp, datos);
            }
            return respuesta(Estado.PAGO_EN_PROCESO, codigoHttp, datos);
        }
        if ("rechazado".equals(resultado) && pedidoValido(pedido)) {
            return respuesta(Estado.PAGO_RECHAZADO, codigoHttp, datos);
        }
        if ("en_proceso".equals(resultado) && pedidoValido(pedido)) {
            return respuesta(Estado.PAGO_EN_PROCESO, codigoHttp, datos);
        }
        if ("correccion".equals(resultado) && esArray(datos, "carrito_corregido")
                && esArray(datos, "motivos")) {
            return respuesta(Estado.CORRECCION_COMERCIAL, codigoHttp, datos);
        }
        return invalida(solicitud, codigoHttp, Estado.RESPUESTA_INESPERADA);
    }

    private static CheckoutResponse respuesta(Estado estado, int codigoHttp, JSONObject datos) {
        return new CheckoutResponse(estado, codigoHttp, null, datos, false);
    }

    private static CheckoutResponse invalida(CheckoutRequest solicitud, int codigoHttp,
                                             Estado estado) {
        boolean incierto = solicitud.getOperacion().puedeModificarEstado();
        return new CheckoutResponse(incierto ? Estado.RESULTADO_INCIERTO : estado,
                codigoHttp, "respuesta_fuera_de_contrato", null, incierto);
    }

    private static boolean esExitoso(int codigoHttp) {
        return codigoHttp >= 200 && codigoHttp < 300;
    }

    private static boolean esArray(JSONObject objeto, String clave) {
        return objeto.opt(clave) instanceof JSONArray;
    }

    private static boolean pedidoValido(JSONObject pedido) {
        return pedido != null && numeroPositivo(pedido.opt("id"))
                && texto(pedido, "estado") != null;
    }

    private static boolean numeroPositivo(Object valor) {
        return valor instanceof Number && ((Number) valor).longValue() > 0;
    }

    private static boolean esToken(String token) {
        return token != null && !token.isEmpty();
    }

    private static String texto(JSONObject objeto, String clave) {
        if (objeto == null) {
            return null;
        }
        Object valor = objeto.opt(clave);
        return valor instanceof String ? (String) valor : null;
    }

    public Estado getEstado() {
        return estado;
    }

    public int getCodigoHttp() {
        return codigoHttp;
    }

    public String getCodigoError() {
        return codigoError;
    }

    public JSONObject getDatos() {
        return datos;
    }

    public boolean requiereReconciliacion() {
        return requiereReconciliacion;
    }

    /** Ningún resultado de esta capa habilita un reintento automático. */
    public boolean permiteReintentoAutomatico() {
        return false;
    }
}
