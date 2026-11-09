package com.incancelables.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

@RunWith(AndroidJUnit4.class)
public class CheckoutResponseTest {

    private final CheckoutRequest pedido = CheckoutRequest.crearPedido(
            "ana@example.com", "Ana", "Pérez",
            java.util.Collections.singletonList(new CheckoutRequest.Item(1, 1, 25000)));

    @Test
    public void distinguePedidoCreadoConEmailEnviadoOConFallo() {
        CheckoutResponse enviado = CheckoutResponse.interpretar(pedido, 201,
                "{\"resultado\":\"creado\",\"pedido\":{\"id\":7,\"estado\":\"PEND_VERIF\"},\"email_enviado\":true}");
        assertEquals(CheckoutResponse.Estado.PEDIDO_CREADO, enviado.getEstado());
        assertFalse(enviado.requiereReconciliacion());
        assertTrue(enviado.getDatos().optBoolean("email_enviado"));

        CheckoutResponse sinEmail = CheckoutResponse.interpretar(pedido, 201,
                "{\"resultado\":\"creado\",\"pedido\":{\"id\":7,\"estado\":\"PEND_VERIF\"},\"email_enviado\":false}");
        assertEquals(CheckoutResponse.Estado.PEDIDO_CREADO, sinEmail.getEstado());
        assertFalse(sinEmail.getDatos().optBoolean("email_enviado"));
    }

    @Test
    public void interpretaCorreccionComercialSinMarcarPedidoCreado() {
        CheckoutResponse response = CheckoutResponse.interpretar(pedido, 200,
                "{\"resultado\":\"correccion\",\"carrito_corregido\":[{\"producto_id\":1,\"cantidad\":1,\"precio_unitario\":26000}],\"disponibilidad\":[{\"producto_id\":1,\"disponibilidad\":3}],\"motivos\":[{\"producto_id\":1,\"motivo\":\"precio_cambiado\",\"precio_actual\":26000}]}");
        assertEquals(CheckoutResponse.Estado.CORRECCION_COMERCIAL, response.getEstado());
        assertFalse(response.requiereReconciliacion());
    }

    @Test
    public void interpretaCooldownTokensYRecuperacion() {
        CheckoutRequest resend = CheckoutRequest.reenviarEmail(7);
        CheckoutResponse resent = CheckoutResponse.interpretar(resend, 200,
                "{\"resultado\":\"reenviado\",\"email_verificacion_id\":12}");
        assertEquals(CheckoutResponse.Estado.EMAIL_REENVIADO, resent.getEstado());

        CheckoutResponse cooldown = CheckoutResponse.interpretar(resend, 429,
                "{\"error\":\"cooldown_activo\",\"segundos_restantes\":120}");
        assertEquals(CheckoutResponse.Estado.COOLDOWN_REENVIO, cooldown.getEstado());
        assertEquals("cooldown_activo", cooldown.getCodigoError());

        CheckoutResponse invalid = CheckoutResponse.interpretar(
                CheckoutRequest.validarEmail("token"), 400,
                "{\"error\":\"token_expirado\"}");
        assertEquals(CheckoutResponse.Estado.ERROR_HTTP, invalid.getEstado());
        assertEquals("token_expirado", invalid.getCodigoError());

        CheckoutResponse invalidToken = CheckoutResponse.interpretar(
                CheckoutRequest.validarEmail("token"), 400,
                "{\"error\":\"token_invalido\"}");
        assertEquals("token_invalido", invalidToken.getCodigoError());

        CheckoutResponse pending = CheckoutResponse.interpretar(
                CheckoutRequest.obtenerPedidosVerificados("token"), 409,
                "{\"error\":\"verificacion_pendiente\"}");
        assertEquals(CheckoutResponse.Estado.ERROR_HTTP, pending.getEstado());
        assertEquals(409, pending.getCodigoHttp());

        CheckoutResponse fetched = CheckoutResponse.interpretar(
                CheckoutRequest.obtenerPedidosVerificados("token"), 200,
                "{\"resultado\":\"ok\",\"pedidos\":[{\"id\":7,\"estado\":\"PEND_PAGO\",\"items\":[{\"producto_id\":1,\"cantidad\":1,\"precio_unitario\":25000}]}]}");
        assertEquals(CheckoutResponse.Estado.PEDIDOS_RECUPERADOS, fetched.getEstado());
    }

    @Test
    public void interpretaValidacionYReenvioDeCodigoAndroid() {
        CheckoutRequest validar = CheckoutRequest.validarCodigo(7, "123456");
        CheckoutResponse confirmado = CheckoutResponse.interpretar(validar, 200,
                "{\"resultado\":\"confirmado\",\"token\":\"abc-token-64\",\"pedidos\":[{\"id\":7,\"estado\":\"PEND_PAGO\"}],\"email_verificacion_id\":5}");
        assertEquals(CheckoutResponse.Estado.CODIGO_CONFIRMADO, confirmado.getEstado());
        assertFalse(confirmado.requiereReconciliacion());
        assertEquals("abc-token-64", confirmado.getDatos().getString("token"));

        CheckoutResponse sinToken = CheckoutResponse.interpretar(validar, 200,
                "{\"resultado\":\"confirmado\",\"pedidos\":[{\"id\":7,\"estado\":\"PEND_PAGO\"}],\"email_verificacion_id\":5}");
        assertEquals(CheckoutResponse.Estado.RESULTADO_INCIERTO, sinToken.getEstado());
        assertTrue(sinToken.requiereReconciliacion());

        CheckoutResponse invalido = CheckoutResponse.interpretar(validar, 400,
                "{\"error\":\"codigo_invalido\"}");
        assertEquals(CheckoutResponse.Estado.ERROR_HTTP, invalido.getEstado());
        assertEquals("codigo_invalido", invalido.getCodigoError());
        assertFalse(invalido.requiereReconciliacion());

        CheckoutResponse expirado = CheckoutResponse.interpretar(validar, 400,
                "{\"error\":\"codigo_expirado\"}");
        assertEquals(CheckoutResponse.Estado.ERROR_HTTP, expirado.getEstado());
        assertEquals("codigo_expirado", expirado.getCodigoError());

        CheckoutResponse agotado = CheckoutResponse.interpretar(validar, 429,
                "{\"error\":\"maximo_intentos_alcanzado\"}");
        assertEquals(CheckoutResponse.Estado.ERROR_HTTP, agotado.getEstado());
        assertEquals(429, agotado.getCodigoHttp());
        assertEquals("maximo_intentos_alcanzado", agotado.getCodigoError());

        CheckoutRequest reenviar = CheckoutRequest.reenviarCodigo(7);
        CheckoutResponse reenviado = CheckoutResponse.interpretar(reenviar, 200,
                "{\"resultado\":\"codigo_reenviado\",\"email_verificacion_id\":6,\"codigo_expira_en_segundos\":600}");
        assertEquals(CheckoutResponse.Estado.CODIGO_REENVIADO, reenviado.getEstado());
        assertFalse(reenviado.requiereReconciliacion());

        CheckoutResponse cooldown = CheckoutResponse.interpretar(reenviar, 429,
                "{\"error\":\"cooldown_activo\",\"segundos_restantes\":300}");
        assertEquals(CheckoutResponse.Estado.COOLDOWN_REENVIO, cooldown.getEstado());
        assertEquals(300, cooldown.getDatos().getInt("segundos_restantes"));
        assertFalse(cooldown.requiereReconciliacion());

        CheckoutResponse envioFallido = CheckoutResponse.interpretar(reenviar, 502,
                "{\"error\":\"error_envio_email\"}");
        assertEquals(CheckoutResponse.Estado.ERROR_HTTP, envioFallido.getEstado());
        assertEquals("error_envio_email", envioFallido.getCodigoError());
        assertTrue(envioFallido.requiereReconciliacion());
        assertFalse(envioFallido.permiteReintentoAutomatico());
    }

    @Test
    public void interpretaReemplazoYCierreDelPedido() {
        java.util.List<CheckoutRequest.Item> items =
                java.util.Collections.singletonList(new CheckoutRequest.Item(1, 1, 25000));
        CheckoutResponse replaced = CheckoutResponse.interpretar(
                CheckoutRequest.reemplazarPedido("token", items), 200,
                "{\"resultado\":\"reemplazado\",\"pedido_anterior\":{\"id\":7,\"estado\":\"NO_PAGADO\"},\"pedido\":{\"id\":8,\"estado\":\"PEND_PAGO\"}}");
        assertEquals(CheckoutResponse.Estado.PEDIDO_REEMPLAZADO, replaced.getEstado());

        CheckoutResponse cancelled = CheckoutResponse.interpretar(
                CheckoutRequest.cancelarPedido(8, "token"), 200,
                "{\"resultado\":\"cancelado\",\"pedido\":{\"id\":8,\"estado\":\"NO_PAGADO\"}}");
        assertEquals(CheckoutResponse.Estado.PEDIDO_CANCELADO, cancelled.getEstado());
    }

    @Test
    public void noConfirmaPagoSoloPorResultadoAprobado() {
        CheckoutRequest pay = CheckoutRequest.iniciarPago(7, "token",
                java.util.Collections.singletonList(new CheckoutRequest.Item(1, 1, 25000)), null);

        CheckoutResponse paid = CheckoutResponse.interpretar(pay, 200,
                "{\"resultado\":\"aprobado\",\"pago\":{\"id\":3,\"importe\":25000},\"validacion\":{\"estado\":\"APROBADO\"},\"pedido\":{\"id\":7,\"estado\":\"PAGADO\"}}");
        assertEquals(CheckoutResponse.Estado.PAGO_CONFIRMADO, paid.getEstado());

        CheckoutResponse stockReview = CheckoutResponse.interpretar(pay, 200,
                "{\"resultado\":\"aprobado\",\"pago\":{\"id\":3,\"importe\":25000},\"pedido\":{\"id\":7,\"estado\":\"PAGADO_STOCK_NO_AFECTADO\"}}");
        assertEquals(CheckoutResponse.Estado.PAGO_REQUIERE_REVISION, stockReview.getEstado());

        CheckoutResponse stillProcessing = CheckoutResponse.interpretar(pay, 200,
                "{\"resultado\":\"aprobado\",\"pedido\":{\"id\":7,\"estado\":\"PROCESANDO_PAGO\"}}");
        assertEquals(CheckoutResponse.Estado.PAGO_EN_PROCESO, stillProcessing.getEstado());
        assertFalse(stillProcessing.permiteReintentoAutomatico());

        CheckoutResponse rejected = CheckoutResponse.interpretar(pay, 200,
                "{\"resultado\":\"rechazado\",\"pedido\":{\"id\":7,\"estado\":\"PEND_PAGO\"},\"intentos\":1,\"maximo_intentos\":3}");
        assertEquals(CheckoutResponse.Estado.PAGO_RECHAZADO, rejected.getEstado());
    }

    @Test
    public void reconoceRespuestaInvalidaYErrorHttp() {
        CheckoutResponse invalidJson = CheckoutResponse.interpretar(pedido, 201, "no-json");
        assertEquals(CheckoutResponse.Estado.RESULTADO_INCIERTO, invalidJson.getEstado());
        assertTrue(invalidJson.requiereReconciliacion());
        assertFalse(invalidJson.permiteReintentoAutomatico());

        CheckoutResponse error = CheckoutResponse.interpretar(pedido, 400,
                "{\"error\":\"Email inválido\"}");
        assertEquals(CheckoutResponse.Estado.ERROR_HTTP, error.getEstado());
        assertEquals("Email inválido", error.getCodigoError());
    }
}
