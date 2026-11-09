package com.incancelables.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CheckoutFlowTest {

    private final Carrito carrito = Carrito.obtenerInstancia();
    private final Producto remera = new Producto(8101, "Remera test", "Negra M", 25000, 10);

    @Before
    public void limpiarCarrito() {
        carrito.vaciar();
        carrito.agregar(remera, 2);
        Api.limpiarCompromisoPedido();
    }

    @Test
    public void validaFormularioYEvitaPulsacionesDuplicadas() throws Exception {
        FakeSender sender = new FakeSender();
        CheckoutFlow flow = new CheckoutFlow(sender, carrito);

        flow.realizarPedido("", "Pérez", "ana@example.com");
        assertEquals(CheckoutFlow.Pantalla.ERROR_VALIDACION,
                flow.getEstado().getPantalla());
        assertEquals("nombre", flow.getEstado().getCampoConError());
        assertEquals(0, sender.llamadas);

        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        assertEquals(CheckoutFlow.Pantalla.ENVIANDO_PEDIDO,
                flow.getEstado().getPantalla());
        assertEquals(1, sender.llamadas);
        assertEquals(2, new org.json.JSONObject(sender.solicitud.getCuerpoJson())
                .getJSONArray("items").getJSONObject(0).getInt("cantidad"));
    }

    @Test
    public void marcaApellidoYEmailInvalidosEnElCampoCorrespondiente() {
        FakeSender sender = new FakeSender();
        CheckoutFlow flow = new CheckoutFlow(sender, carrito);

        flow.realizarPedido("Ana", " ", "ana@example.com");
        assertEquals(CheckoutFlow.Pantalla.ERROR_VALIDACION, flow.getEstado().getPantalla());
        assertEquals("apellido", flow.getEstado().getCampoConError());

        flow.realizarPedido("Ana", "Pérez", "no-es-email");
        assertEquals(CheckoutFlow.Pantalla.ERROR_VALIDACION, flow.getEstado().getPantalla());
        assertEquals("email", flow.getEstado().getCampoConError());
        assertEquals(0, sender.llamadas);
    }

    @Test
    public void pedidoCreadoPasaAlIngresoDeCodigoYPermiteReenviar() throws Exception {
        FakeSender sender = new FakeSender();
        CheckoutFlow flow = new CheckoutFlow(sender, carrito);
        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        assertEquals(CheckoutRequest.Operacion.CREAR_PEDIDO, sender.solicitud.getOperacion());
        assertEquals("android", new org.json.JSONObject(sender.solicitud.getCuerpoJson())
                .getString("canal"));
        sender.responder(201,
                "{\"resultado\":\"creado\",\"pedido\":{\"id\":91,\"estado\":\"PEND_VERIF\"},\"email_enviado\":true,\"canal\":\"android\",\"codigo_expira_en_segundos\":600}");

        assertEquals(CheckoutFlow.Pantalla.INGRESO_CODIGO, flow.getEstado().getPantalla());
        assertEquals(91, flow.getEstado().getPedidoId());
        assertTrue(flow.getEstado().isEmailEnviado());
        assertEquals(2, carrito.getCantidad(remera.getId()));

        flow.reenviarCodigo();
        assertEquals(CheckoutRequest.Operacion.REENVIAR_CODIGO, sender.solicitud.getOperacion());
        assertEquals("/email-verificaciones/codigo", sender.solicitud.getRuta());
        assertEquals(91, new org.json.JSONObject(sender.solicitud.getCuerpoJson())
                .getInt("pedido_id"));
        sender.responder(200,
                "{\"resultado\":\"codigo_reenviado\",\"email_verificacion_id\":22,\"codigo_expira_en_segundos\":600}");
        assertEquals(CheckoutFlow.Pantalla.INGRESO_CODIGO, flow.getEstado().getPantalla());
        assertTrue(flow.getEstado().isEmailEnviado());

        assertEquals(CheckoutFlow.Pantalla.INGRESO_CODIGO, flow.getEstado().getPantalla());
        assertEquals(2, carrito.getCantidad(remera.getId()));
    }

    @Test
    public void validaCodigoYConservaElTokenSoloEnMemoria() throws Exception {
        FakeSender sender = new FakeSender();
        CheckoutFlow flow = new CheckoutFlow(sender, carrito);
        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        sender.responder(201,
                "{\"resultado\":\"creado\",\"pedido\":{\"id\":91,\"estado\":\"PEND_VERIF\"},\"email_enviado\":true}");

        flow.validarCodigo("123456");
        assertEquals(CheckoutRequest.Operacion.VALIDAR_CODIGO, sender.solicitud.getOperacion());
        assertEquals("/email-verificaciones/validar-codigo", sender.solicitud.getRuta());
        assertEquals(91, new org.json.JSONObject(sender.solicitud.getCuerpoJson())
                .getInt("pedido_id"));
        assertEquals(CheckoutFlow.Pantalla.VALIDANDO_CODIGO, flow.getEstado().getPantalla());

        flow.validarCodigo("654321");
        flow.reenviarCodigo();
        assertEquals(2, sender.llamadas);

        sender.responder(200,
                "{\"resultado\":\"confirmado\",\"token\":\"tok-secreto\",\"pedidos\":[{\"id\":91,\"estado\":\"PEND_PAGO\"}],\"email_verificacion_id\":5}");
        assertEquals(CheckoutFlow.Pantalla.PAGO_RECUPERANDO, flow.getEstado().getPantalla());
        assertEquals(CheckoutRequest.Operacion.OBTENER_PEDIDOS_VERIFICADOS,
                sender.solicitud.getOperacion());
        assertEquals("tok-secreto", new org.json.JSONObject(sender.solicitud.getCuerpoJson())
                .getString("token"));
        sender.responder(200,
                "{\"resultado\":\"ok\",\"pedidos\":[{\"id\":91,\"estado\":\"PEND_PAGO\",\"items\":[{\"producto_id\":8101,\"cantidad\":2,\"precio_unitario\":25000}]}]}");
        assertEquals(CheckoutFlow.Pantalla.PAGO_LISTO, flow.getEstado().getPantalla());
        assertEquals(91, flow.getEstado().getPedidoId());
        assertEquals("tok-secreto", flow.obtenerTokenVerificacion());
        assertEquals(2, carrito.getCantidad(remera.getId()));

        assertEquals(3, sender.llamadas);
        flow.reintentarPago();
        assertEquals(4, sender.llamadas);
        assertEquals(CheckoutFlow.Pantalla.PAGO_PROCESANDO, flow.getEstado().getPantalla());
        assertEquals(CheckoutRequest.Operacion.INICIAR_PAGO, sender.solicitud.getOperacion());
        org.json.JSONObject cuerpoPago = new org.json.JSONObject(sender.solicitud.getCuerpoJson());
        assertEquals("tok-secreto", cuerpoPago.getString("token"));
        assertEquals("iniciar_pago", cuerpoPago.getString("accion"));
        assertEquals("DUMMY", cuerpoPago.getString("proveedor_id"));
        assertEquals("APROBADO", cuerpoPago.getString("simulacion"));
        sender.responder(200,
                "{\"resultado\":\"aprobado\",\"pago\":{\"id\":3,\"importe\":50000},\"validacion\":{\"estado\":\"APROBADO\",\"referencia_proveedor\":\"DUMMY-3\"},\"pedido\":{\"id\":91,\"estado\":\"PAGADO\"}}");
        assertEquals(CheckoutFlow.Pantalla.PAGO_CONFIRMADO, flow.getEstado().getPantalla());
        assertTrue(carrito.getItems().isEmpty());
    }

    @Test
    public void pagoRechazadoPermiteSoloReintentoManualYVaciaAlAprobar() throws Exception {
        FakeSender sender = new FakeSender();
        CheckoutFlow flow = new CheckoutFlow(sender, carrito);
        crearYVerificar(flow, sender);
        assertEquals(CheckoutRequest.Operacion.INICIAR_PAGO, sender.solicitud.getOperacion());

        sender.responder(200,
                "{\"resultado\":\"rechazado\",\"pedido\":{\"id\":91,\"estado\":\"PEND_PAGO\"},\"intentos\":1,\"maximo_intentos\":3,\"pago\":{\"id\":3,\"importe\":50000},\"validacion\":{\"estado\":\"RECHAZADO\"}}");
        assertEquals(CheckoutFlow.Pantalla.PAGO_RECHAZADO, flow.getEstado().getPantalla());
        assertEquals(2, carrito.getCantidad(remera.getId()));
        assertTrue(flow.getEstado().getMensaje().contains("Intento 1 de 3"));

        int llamadasAntes = sender.llamadas;
        flow.reintentarPago();
        flow.reintentarPago();
        assertEquals(llamadasAntes + 1, sender.llamadas);
        sender.responder(200,
                "{\"resultado\":\"aprobado\",\"pago\":{\"id\":4,\"importe\":50000},\"validacion\":{\"estado\":\"APROBADO\"},\"pedido\":{\"id\":91,\"estado\":\"PAGADO\"}}");
        assertEquals(CheckoutFlow.Pantalla.PAGO_CONFIRMADO, flow.getEstado().getPantalla());
        assertTrue(carrito.getItems().isEmpty());
    }

    @Test
    public void pagoRechazadoSinIntentosDisponiblesNoPermiteReintentar() throws Exception {
        FakeSender sender = new FakeSender();
        CheckoutFlow flow = new CheckoutFlow(sender, carrito);
        crearYVerificar(flow, sender);
        sender.responder(200,
                "{\"resultado\":\"rechazado\",\"pedido\":{\"id\":91,\"estado\":\"NO_PAGADO\"},\"intentos\":3,\"maximo_intentos\":3,\"pago\":{\"id\":5,\"importe\":50000},\"validacion\":{\"estado\":\"RECHAZADO\"}}");
        assertEquals(CheckoutFlow.Pantalla.PAGO_RESULTADO, flow.getEstado().getPantalla());
        int llamadas = sender.llamadas;
        flow.reintentarPago();
        assertEquals(llamadas, sender.llamadas);
        assertEquals(2, carrito.getCantidad(remera.getId()));
    }

    @Test
    public void errorDeRedDePagoNoSeReintentaAutomaticamente() throws Exception {
        FakeSender sender = new FakeSender();
        CheckoutFlow flow = new CheckoutFlow(sender, carrito);
        crearYVerificar(flow, sender);
        int llamadasAntesDeFallo = sender.llamadas;
        sender.fallarRed();

        assertEquals(CheckoutFlow.Pantalla.PAGO_ERROR, flow.getEstado().getPantalla());
        assertEquals(2, carrito.getCantidad(remera.getId()));
        assertEquals(llamadasAntesDeFallo, sender.llamadas);

        flow.reintentarPago();
        assertEquals(llamadasAntesDeFallo + 1, sender.llamadas);
        assertEquals(CheckoutRequest.Operacion.INICIAR_PAGO, sender.solicitud.getOperacion());
    }

    @Test
    public void correccionDePagoReemplazaElPedidoAntesDeReintentar() throws Exception {
        FakeSender sender = new FakeSender();
        CheckoutFlow flow = new CheckoutFlow(sender, carrito);
        crearYVerificar(flow, sender);
        sender.responder(200,
                "{\"resultado\":\"correccion\",\"pedido\":{\"id\":91,\"estado\":\"PEND_PAGO\"},\"carrito_corregido\":[{\"producto_id\":8101,\"cantidad\":1,\"precio_unitario\":26000}],\"disponibilidad\":[{\"producto_id\":8101,\"disponibilidad\":5}],\"motivos\":[{\"producto_id\":8101,\"motivo\":\"precio_cambiado\",\"precio_actual\":26000}]}");
        assertEquals(CheckoutFlow.Pantalla.CORRECCION_COMERCIAL, flow.getEstado().getPantalla());
        assertTrue(flow.aplicarCorreccionAlCarrito());
        assertEquals(CheckoutFlow.Pantalla.PAGO_LISTO, flow.getEstado().getPantalla());
        assertEquals(1, carrito.getCantidad(remera.getId()));

        flow.reintentarPago();
        assertEquals(CheckoutRequest.Operacion.REEMPLAZAR_PEDIDO, sender.solicitud.getOperacion());
        int llamadasConReemplazoEnCurso = sender.llamadas;
        flow.reintentarPago();
        assertEquals(llamadasConReemplazoEnCurso, sender.llamadas);
        sender.responder(200,
                "{\"resultado\":\"reemplazado\",\"pedido_anterior\":{\"id\":91,\"estado\":\"NO_PAGADO\"},\"pedido\":{\"id\":92,\"estado\":\"PEND_PAGO\"}}");
        assertEquals(CheckoutRequest.Operacion.INICIAR_PAGO, sender.solicitud.getOperacion());
        assertEquals("/pedidos/92/pago", sender.solicitud.getRuta());
        sender.responder(200,
                "{\"resultado\":\"aprobado\",\"pago\":{\"id\":5,\"importe\":26000},\"validacion\":{\"estado\":\"APROBADO\"},\"pedido\":{\"id\":92,\"estado\":\"PAGADO\"}}");
        assertEquals(CheckoutFlow.Pantalla.PAGO_CONFIRMADO, flow.getEstado().getPantalla());
        assertTrue(carrito.getItems().isEmpty());
    }

    private void crearYVerificar(CheckoutFlow flow, FakeSender sender) throws Exception {
        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        sender.responder(201,
                "{\"resultado\":\"creado\",\"pedido\":{\"id\":91,\"estado\":\"PEND_VERIF\"},\"email_enviado\":true}");
        flow.validarCodigo("123456");
        sender.responder(200,
                "{\"resultado\":\"confirmado\",\"token\":\"tok-secreto\",\"pedidos\":[{\"id\":91,\"estado\":\"PEND_PAGO\"}],\"email_verificacion_id\":5}");
        sender.responder(200,
                "{\"resultado\":\"ok\",\"pedidos\":[{\"id\":91,\"estado\":\"PEND_PAGO\",\"items\":[{\"producto_id\":8101,\"cantidad\":2,\"precio_unitario\":25000}]}]}");
        flow.reintentarPago();
    }

    @Test
    public void codigoInvalidoYCooldownVuelvenAlIngresoConservandoElPedido() throws Exception {
        FakeSender sender = new FakeSender();
        CheckoutFlow flow = new CheckoutFlow(sender, carrito);
        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        sender.responder(201,
                "{\"resultado\":\"creado\",\"pedido\":{\"id\":91,\"estado\":\"PEND_VERIF\"},\"email_enviado\":true}");

        flow.validarCodigo("12");
        assertEquals(CheckoutFlow.Pantalla.INGRESO_CODIGO, flow.getEstado().getPantalla());
        assertEquals("codigo", flow.getEstado().getCampoConError());
        assertEquals(1, sender.llamadas);

        flow.validarCodigo("111111");
        sender.responder(400, "{\"error\":\"codigo_invalido\"}");
        assertEquals(CheckoutFlow.Pantalla.INGRESO_CODIGO, flow.getEstado().getPantalla());
        assertEquals(91, flow.getEstado().getPedidoId());
        assertEquals(2, sender.llamadas);

        flow.reenviarCodigo();
        sender.responder(429, "{\"error\":\"cooldown_activo\",\"segundos_restantes\":180}");
        assertEquals(CheckoutFlow.Pantalla.INGRESO_CODIGO, flow.getEstado().getPantalla());
        assertTrue(flow.getEstado().getMensaje().contains("180"));
        assertEquals(3, sender.llamadas);
    }

    @Test
    public void errorFatalDeVerificacionCierraElIngresoDeCodigo() throws Exception {
        FakeSender sender = new FakeSender();
        CheckoutFlow flow = new CheckoutFlow(sender, carrito);
        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        sender.responder(201,
                "{\"resultado\":\"creado\",\"pedido\":{\"id\":91,\"estado\":\"PEND_VERIF\"},\"email_enviado\":true}");

        flow.validarCodigo("123456");
        sender.responder(409, "{\"error\":\"pedido_vencido\"}");
        assertEquals(CheckoutFlow.Pantalla.ERROR, flow.getEstado().getPantalla());
        assertEquals(0, flow.getEstado().getPedidoId());

        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        assertEquals(3, sender.llamadas);
    }

    @Test
    public void restauraElPedidoTrasLaMuerteDelProcesoSinDuplicarlo() {
        CheckoutFlow restaurado = new CheckoutFlow(new FakeSender(), carrito);
        restaurado.restaurar(91, "INGRESO_CODIGO", true, null);
        assertEquals(CheckoutFlow.Pantalla.INGRESO_CODIGO, restaurado.getEstado().getPantalla());
        assertEquals(91, restaurado.getEstado().getPedidoId());
        assertTrue(restaurado.getEstado().isEmailEnviado());

        CheckoutFlow interrumpido = new CheckoutFlow(new FakeSender(), carrito);
        interrumpido.restaurar(91, "VALIDANDO_CODIGO", true, "Validando el código…");
        assertEquals(CheckoutFlow.Pantalla.INGRESO_CODIGO,
                interrumpido.getEstado().getPantalla());
        assertEquals(91, interrumpido.getEstado().getPedidoId());

        CheckoutFlow confirmado = new CheckoutFlow(new FakeSender(), carrito);
        confirmado.restaurar(91, "CODIGO_CONFIRMADO", true, "Tu email fue verificado.");
        assertEquals(CheckoutFlow.Pantalla.PAGO_RESULTADO,
                confirmado.getEstado().getPantalla());

        CheckoutFlow sinPedido = new CheckoutFlow(new FakeSender(), carrito);
        sinPedido.restaurar(0, "INGRESO_CODIGO", false, null);
        assertEquals(CheckoutFlow.Pantalla.FORMULARIO, sinPedido.getEstado().getPantalla());

        CheckoutFlow otraPantalla = new CheckoutFlow(new FakeSender(), carrito);
        otraPantalla.restaurar(91, "FORMULARIO", false, null);
        assertEquals(CheckoutFlow.Pantalla.FORMULARIO, otraPantalla.getEstado().getPantalla());

        FakeSender inciertoSender = new FakeSender();
        CheckoutFlow pagoInterrumpido = new CheckoutFlow(inciertoSender, carrito);
        pagoInterrumpido.restaurar(91, "PAGO_LISTO", true, "Tu pedido está listo para pagar.");
        assertEquals(CheckoutFlow.Pantalla.PAGO_RESULTADO,
                pagoInterrumpido.getEstado().getPantalla());
        pagoInterrumpido.realizarPedido("Ana", "Pérez", "ana@example.com");
        assertEquals(0, inciertoSender.llamadas);
    }

    @Test
    public void correccionSeMuestraYSeAplicaSoloTrasAceptacionExplicita() throws Exception {
        FakeSender sender = new FakeSender();
        CheckoutFlow flow = new CheckoutFlow(sender, carrito);
        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        sender.responder(200,
                "{\"resultado\":\"correccion\",\"carrito_corregido\":[{\"producto_id\":8101,\"cantidad\":1,\"precio_unitario\":26000}],\"disponibilidad\":[{\"producto_id\":8101,\"disponibilidad\":5}],\"motivos\":[{\"producto_id\":8101,\"motivo\":\"precio_cambiado\",\"precio_actual\":26000}]}");

        assertEquals(CheckoutFlow.Pantalla.CORRECCION_COMERCIAL,
                flow.getEstado().getPantalla());
        assertEquals(2, carrito.getCantidad(remera.getId()));
        assertEquals(1, sender.llamadas);

        assertTrue(flow.aplicarCorreccionAlCarrito());
        assertEquals(CheckoutFlow.Pantalla.FORMULARIO, flow.getEstado().getPantalla());
        assertEquals(1, carrito.getCantidad(remera.getId()));
        assertEquals(26000, carrito.getItems().get(0).getProducto().getPrecio(), 0.0);
        assertEquals(5, carrito.getItems().get(0).getProducto().getDisponibilidad());
        assertEquals(1, sender.llamadas);
    }

    @Test
    public void errorDeValidacionYResultadoInciertoNoBorranNiRepitenElPedido() {
        FakeSender sender = new FakeSender();
        CheckoutFlow flow = new CheckoutFlow(sender, carrito);
        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        sender.responder(400, "{\"error\":\"Email inválido\"}");
        assertEquals(CheckoutFlow.Pantalla.ERROR_VALIDACION, flow.getEstado().getPantalla());
        assertEquals("email", flow.getEstado().getCampoConError());
        assertEquals(2, carrito.getCantidad(remera.getId()));

        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        sender.responder(201, "respuesta no JSON");
        assertEquals(CheckoutFlow.Pantalla.RESULTADO_INCIERTO, flow.getEstado().getPantalla());
        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        assertEquals(2, sender.llamadas);
        assertEquals(2, carrito.getCantidad(remera.getId()));
    }

    private static final class FakeSender implements CheckoutSender {
        int llamadas;
        CheckoutRequest solicitud;
        Callback callback;

        @Override
        public void enviar(CheckoutRequest request, Callback completion) {
            llamadas++;
            solicitud = request;
            callback = completion;
        }

        void responder(int status, String body) {
            CheckoutResponse response = CheckoutResponse.interpretar(solicitud, status, body);
            callback.alCompletar(response);
        }

        void fallarRed() {
            callback.alCompletar(CheckoutResponse.errorDeRed(solicitud));
        }
    }
}
