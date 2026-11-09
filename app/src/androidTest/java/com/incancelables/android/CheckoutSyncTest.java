package com.incancelables.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CheckoutSyncTest {

    private final Carrito carrito = Carrito.obtenerInstancia();
    private final Producto remera = new Producto(8201, "Remera sync", "", 25000, 8);
    private FakeSender sender;
    private int refrescos;
    private CheckoutFlow flow;

    @Before
    public void preparar() {
        carrito.vaciar();
        carrito.agregar(remera, 2);
        Api.limpiarCompromisoPedido();
        sender = new FakeSender();
        refrescos = 0;
        flow = new CheckoutFlow(sender, carrito, () -> refrescos++);
    }

    @Test
    public void recuperacionYPagoAprobadoRefrescanCatalogoSoloEnSusEventos() throws Exception {
        crearPedido();
        assertEquals(0, refrescos); // La respuesta creado del frontend no recarga catálogo.

        flow.validarCodigo("123456");
        sender.responder(200,
                "{\"resultado\":\"confirmado\",\"token\":\"token-sync\",\"pedidos\":[{\"id\":81,\"estado\":\"PEND_PAGO\"}],\"email_verificacion_id\":4}");
        assertEquals(CheckoutRequest.Operacion.OBTENER_PEDIDOS_VERIFICADOS,
                sender.solicitud.getOperacion());
        sender.responder(200,
                "{\"resultado\":\"ok\",\"pedidos\":[{\"id\":81,\"estado\":\"PEND_PAGO\",\"items\":[{\"producto_id\":8201,\"cantidad\":2,\"precio_unitario\":25000}]}]}");
        assertEquals(1, refrescos);
        assertEquals(CheckoutFlow.Pantalla.PAGO_LISTO, flow.getEstado().getPantalla());

        flow.reintentarPago();
        sender.responder(200,
                "{\"resultado\":\"aprobado\",\"pago\":{\"id\":7,\"importe\":50000},\"validacion\":{\"estado\":\"APROBADO\"},\"pedido\":{\"id\":81,\"estado\":\"PAGADO\"}}");
        assertEquals(2, refrescos);
        assertTrue(carrito.getItems().isEmpty());
    }

    @Test
    public void correccionYPosponerPedidoRefrescanUnaVezPorEvento() throws Exception {
        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        sender.responder(200,
                "{\"resultado\":\"correccion\",\"carrito_corregido\":[{\"producto_id\":8201,\"cantidad\":1,\"precio_unitario\":26000}],\"disponibilidad\":[{\"producto_id\":8201,\"disponibilidad\":6}],\"motivos\":[{\"producto_id\":8201,\"motivo\":\"precio_cambiado\",\"precio_actual\":26000}]}");
        assertEquals(0, refrescos);
        assertTrue(flow.aplicarCorreccionAlCarrito());
        assertEquals(1, refrescos);

        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        sender.responder(201,
                "{\"resultado\":\"creado\",\"pedido\":{\"id\":82,\"estado\":\"PEND_VERIF\"},\"email_enviado\":true}");
        assertEquals(1, refrescos);
        flow.posponerVerificacion();
        flow.posponerVerificacion();
        assertEquals(2, refrescos);
    }

    @Test
    public void rechazoDePagoSinCambiarPedidoNoSolicitaCatalogoOtraVez() throws Exception {
        crearPedido();
        flow.validarCodigo("123456");
        sender.responder(200,
                "{\"resultado\":\"confirmado\",\"token\":\"token-sync\",\"pedidos\":[{\"id\":81,\"estado\":\"PEND_PAGO\"}],\"email_verificacion_id\":4}");
        sender.responder(200,
                "{\"resultado\":\"ok\",\"pedidos\":[{\"id\":81,\"estado\":\"PEND_PAGO\",\"items\":[{\"producto_id\":8201,\"cantidad\":2,\"precio_unitario\":25000}]}]}");
        flow.reintentarPago();
        sender.responder(200,
                "{\"resultado\":\"rechazado\",\"pedido\":{\"id\":81,\"estado\":\"PEND_PAGO\"},\"intentos\":1,\"maximo_intentos\":3,\"pago\":{\"id\":8,\"importe\":50000},\"validacion\":{\"estado\":\"RECHAZADO\"}}");
        assertEquals(1, refrescos);
        assertEquals(2, carrito.getCantidad(remera.getId()));
    }

    @Test
    public void correccionDePagoRefrescaAlAplicarsePeroNoAlReemplazarNiRechazar() throws Exception {
        crearPedido();
        verificarYRecuperar();
        flow.reintentarPago();
        sender.responder(200,
                "{\"resultado\":\"correccion\",\"pedido\":{\"id\":81,\"estado\":\"PEND_PAGO\"},\"carrito_corregido\":[{\"producto_id\":8201,\"cantidad\":1,\"precio_unitario\":26000}],\"motivos\":[{\"producto_id\":8201,\"motivo\":\"precio_cambiado\"}]}");
        assertEquals(1, refrescos);

        assertTrue(flow.aplicarCorreccionAlCarrito());
        assertEquals(2, refrescos);
        flow.reintentarPago();
        sender.responder(200,
                "{\"resultado\":\"reemplazado\",\"pedido_anterior\":{\"id\":81,\"estado\":\"NO_PAGADO\"},\"pedido\":{\"id\":82,\"estado\":\"PEND_PAGO\"}}");
        assertEquals(CheckoutRequest.Operacion.INICIAR_PAGO, sender.solicitud.getOperacion());
        assertEquals(2, refrescos);

        sender.responder(200,
                "{\"resultado\":\"rechazado\",\"pedido\":{\"id\":82,\"estado\":\"PEND_PAGO\"},\"intentos\":1,\"maximo_intentos\":3,\"pago\":{\"id\":9,\"importe\":26000},\"validacion\":{\"estado\":\"RECHAZADO\"}}");
        assertEquals(2, refrescos);
    }

    private void crearPedido() throws Exception {
        flow.realizarPedido("Ana", "Pérez", "ana@example.com");
        sender.responder(201,
                "{\"resultado\":\"creado\",\"pedido\":{\"id\":81,\"estado\":\"PEND_VERIF\"},\"email_enviado\":true}");
    }

    private void verificarYRecuperar() throws Exception {
        flow.validarCodigo("123456");
        sender.responder(200,
                "{\"resultado\":\"confirmado\",\"token\":\"token-sync\",\"pedidos\":[{\"id\":81,\"estado\":\"PEND_PAGO\"}],\"email_verificacion_id\":4}");
        sender.responder(200,
                "{\"resultado\":\"ok\",\"pedidos\":[{\"id\":81,\"estado\":\"PEND_PAGO\",\"items\":[{\"producto_id\":8201,\"cantidad\":2,\"precio_unitario\":25000}]}]}");
    }

    private static final class FakeSender implements CheckoutSender {
        CheckoutRequest solicitud;
        Callback callback;

        @Override
        public void enviar(CheckoutRequest request, Callback completion) {
            solicitud = request;
            callback = completion;
        }

        void responder(int status, String body) {
            callback.alCompletar(CheckoutResponse.interpretar(solicitud, status, body));
        }
    }
}
