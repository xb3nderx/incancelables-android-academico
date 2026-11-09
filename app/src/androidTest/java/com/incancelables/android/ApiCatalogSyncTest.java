package com.incancelables.android;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class ApiCatalogSyncTest {

    @After
    public void limpiarCompromiso() {
        Api.limpiarCompromisoPedido();
    }

    @Test
    public void agregaAlCatalogoLaDisponibilidadYaComprometidaPorElPedidoPropio() {
        Producto recibidoDeApi = new Producto(8301, "Producto", "", 1000, 7);
        Api.definirCompromisoPedido(Arrays.asList(
                new CheckoutRequest.Item(8301, 2, 1000),
                new CheckoutRequest.Item(8301, 1, 1000)));

        List<Producto> presentados = Api.aplicarCompromisoPedido(
                Collections.singletonList(recibidoDeApi));

        assertEquals(7, recibidoDeApi.getDisponibilidad());
        assertEquals(10, presentados.get(0).getDisponibilidad());
        assertEquals(1000, presentados.get(0).getPrecio(), 0.0);

        Api.limpiarCompromisoPedido();
        assertEquals(7, Api.aplicarCompromisoPedido(
                Collections.singletonList(recibidoDeApi)).get(0).getDisponibilidad());
    }
}
