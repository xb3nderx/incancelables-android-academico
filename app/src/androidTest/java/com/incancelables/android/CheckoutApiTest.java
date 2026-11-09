package com.incancelables.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(AndroidJUnit4.class)
public class CheckoutApiTest {

    private static final Executor DIRECTO = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    @Test
    public void timeoutDeCreacionSeMarcaInciertoYNoReintenta() {
        final AtomicInteger llamadas = new AtomicInteger();
        CheckoutApi api = new CheckoutApi(request -> {
            llamadas.incrementAndGet();
            throw new SocketTimeoutException("timeout fixture");
        }, DIRECTO, DIRECTO);

        final CheckoutResponse[] recibido = new CheckoutResponse[1];
        api.enviar(pedidoFixture(), response -> recibido[0] = response);

        assertEquals(1, llamadas.get());
        assertNotNull(recibido[0]);
        assertEquals(CheckoutResponse.Estado.RESULTADO_INCIERTO, recibido[0].getEstado());
        assertTrue(recibido[0].requiereReconciliacion());
        assertFalse(recibido[0].permiteReintentoAutomatico());
    }

    @Test
    public void falloDeLecturaDePedidosNoSeConfundeConUnaMutacionIncierta() {
        CheckoutApi api = new CheckoutApi(request -> {
            throw new IOException("network fixture");
        }, DIRECTO, DIRECTO);

        final CheckoutResponse[] recibido = new CheckoutResponse[1];
        api.enviar(CheckoutRequest.obtenerPedidosVerificados("token-fixture"),
                response -> recibido[0] = response);

        assertNotNull(recibido[0]);
        assertEquals(CheckoutResponse.Estado.ERROR_RED, recibido[0].getEstado());
        assertFalse(recibido[0].requiereReconciliacion());
    }

    @Test
    public void errorHttpDelServidorSePreservaSinReintentoAutomatico() {
        CheckoutApi api = new CheckoutApi(request ->
                new CheckoutApi.RespuestaHttp(502, "{\"error\":\"error_envio_email\"}"),
                DIRECTO, DIRECTO);

        final CheckoutResponse[] recibido = new CheckoutResponse[1];
        api.enviar(CheckoutRequest.reenviarEmail(7), response -> recibido[0] = response);

        assertEquals(CheckoutResponse.Estado.ERROR_HTTP, recibido[0].getEstado());
        assertEquals(502, recibido[0].getCodigoHttp());
        assertEquals("error_envio_email", recibido[0].getCodigoError());
        assertFalse(recibido[0].permiteReintentoAutomatico());
    }

    private static CheckoutRequest pedidoFixture() {
        return CheckoutRequest.crearPedido("ana@example.com", "Ana", "Pérez",
                Collections.singletonList(new CheckoutRequest.Item(1, 1, 25000)));
    }
}
