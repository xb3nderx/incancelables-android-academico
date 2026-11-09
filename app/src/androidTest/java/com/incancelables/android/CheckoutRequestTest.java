package com.incancelables.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class CheckoutRequestTest {

    @Test
    public void pedidoUsaExactamenteLosCamposDelContratoPhp() throws Exception {
        CheckoutRequest request = CheckoutRequest.crearPedido(
                "comprador@example.com", " Ana ", " Pérez ",
                Collections.singletonList(new CheckoutRequest.Item(5, 2, 18000.0)));

        assertEquals("/pedidos", request.getRuta());
        JSONObject body = new JSONObject(request.getCuerpoJson());
        assertKeys(body, "email", "nombre", "apellido", "items", "canal");
        assertEquals("comprador@example.com", body.getString("email"));
        assertEquals("Ana", body.getString("nombre"));
        assertEquals("Pérez", body.getString("apellido"));
        assertEquals("android", body.getString("canal"));

        JSONArray items = body.getJSONArray("items");
        assertEquals(1, items.length());
        JSONObject item = items.getJSONObject(0);
        assertKeys(item, "producto_id", "cantidad", "precio_unitario");
        assertEquals(5, item.getInt("producto_id"));
        assertEquals(2, item.getInt("cantidad"));
        assertEquals(18000.0, item.getDouble("precio_unitario"), 0.0);
    }

    @Test
    public void solicitudesDeVerificacionReenvioYCancelacionNoAgreganCampos() throws Exception {
        JSONObject reenvio = new JSONObject(CheckoutRequest.reenviarEmail(41).getCuerpoJson());
        assertKeys(reenvio, "pedido_id");
        assertEquals(41, reenvio.getInt("pedido_id"));

        CheckoutRequest validar = CheckoutRequest.validarEmail("abc-token");
        assertEquals("/email-verificaciones/validar", validar.getRuta());
        assertKeys(new JSONObject(validar.getCuerpoJson()), "token");

        CheckoutRequest consultar = CheckoutRequest.obtenerPedidosVerificados("abc-token");
        assertEquals("/email-verificaciones/pedidos", consultar.getRuta());
        assertKeys(new JSONObject(consultar.getCuerpoJson()), "token");

        CheckoutRequest cancelar = CheckoutRequest.cancelarPedido(41, "abc-token");
        assertEquals("/pedidos/41/cancelar", cancelar.getRuta());
        assertKeys(new JSONObject(cancelar.getCuerpoJson()), "token");
    }

    @Test
    public void solicitudesDeCodigoUsanLosEndpointsAprobados() throws Exception {
        CheckoutRequest validar = CheckoutRequest.validarCodigo(7, "123456");
        assertEquals("/email-verificaciones/validar-codigo", validar.getRuta());
        assertEquals(CheckoutRequest.Operacion.VALIDAR_CODIGO, validar.getOperacion());
        JSONObject validarBody = new JSONObject(validar.getCuerpoJson());
        assertKeys(validarBody, "pedido_id", "codigo");
        assertEquals(7, validarBody.getInt("pedido_id"));
        assertEquals("123456", validarBody.getString("codigo"));

        CheckoutRequest reenviar = CheckoutRequest.reenviarCodigo(7);
        assertEquals("/email-verificaciones/codigo", reenviar.getRuta());
        assertEquals(CheckoutRequest.Operacion.REENVIAR_CODIGO, reenviar.getOperacion());
        assertKeys(new JSONObject(reenviar.getCuerpoJson()), "pedido_id");
    }

    @Test
    public void reemplazoYPagoSerializanLosContratosAprobados() throws Exception {
        java.util.List<CheckoutRequest.Item> items = Arrays.asList(
                new CheckoutRequest.Item(1, 3, 25000),
                new CheckoutRequest.Item(5, 1, 18000));

        CheckoutRequest reemplazar = CheckoutRequest.reemplazarPedido("tok", items);
        assertEquals("/email-verificaciones/pedidos/reemplazo", reemplazar.getRuta());
        JSONObject replaceBody = new JSONObject(reemplazar.getCuerpoJson());
        assertKeys(replaceBody, "token", "items");
        assertEquals(2, replaceBody.getJSONArray("items").length());

        CheckoutRequest pagar = CheckoutRequest.iniciarPago(41, "tok", items, "APROBADO");
        assertEquals("/pedidos/41/pago", pagar.getRuta());
        JSONObject payBody = new JSONObject(pagar.getCuerpoJson());
        assertKeys(payBody, "token", "accion", "proveedor_id", "items", "simulacion");
        assertEquals("iniciar_pago", payBody.getString("accion"));
        assertEquals("DUMMY", payBody.getString("proveedor_id"));
        assertEquals("APROBADO", payBody.getString("simulacion"));

        CheckoutRequest pagarSinSimulacion =
                CheckoutRequest.iniciarPago(41, "tok", items, null);
        JSONObject payDefaultBody = new JSONObject(pagarSinSimulacion.getCuerpoJson());
        assertFalse(payDefaultBody.has("simulacion"));

        CheckoutRequest aceptarCorreccion = CheckoutRequest.aceptarCorreccion(41, "tok", items);
        JSONObject correctionBody = new JSONObject(aceptarCorreccion.getCuerpoJson());
        assertKeys(correctionBody, "token", "items", "accion");
        assertEquals("aceptar_correccion", correctionBody.getString("accion"));
    }

    @Test
    public void rechazaDatosEstructuralmenteInvalidosAntesDelTransporte() {
        esperarInvalido(() -> CheckoutRequest.crearPedido("x", "Ana", "Pérez",
                Collections.singletonList(new CheckoutRequest.Item(1, 1, 10))));
        esperarInvalido(() -> CheckoutRequest.crearPedido("ana@example.com", " ", "Pérez",
                Collections.singletonList(new CheckoutRequest.Item(1, 1, 10))));
        esperarInvalido(() -> CheckoutRequest.crearPedido("ana@example.com", "Ana", "Pérez",
                Collections.<CheckoutRequest.Item>emptyList()));
        esperarInvalido(() -> CheckoutRequest.crearPedido("ana@example.com", "Ana", "Pérez",
                Collections.singletonList(new CheckoutRequest.Item(0, 1, 10))));
        esperarInvalido(() -> CheckoutRequest.crearPedido("ana@example.com", "Ana", "Pérez",
                Collections.singletonList(new CheckoutRequest.Item(1, 0, 10))));
        esperarInvalido(() -> CheckoutRequest.validarEmail(" "));
        esperarInvalido(() -> CheckoutRequest.cancelarPedido(0, "token"));
        esperarInvalido(() -> CheckoutRequest.validarCodigo(0, "123456"));
        esperarInvalido(() -> CheckoutRequest.validarCodigo(7, "123"));
        esperarInvalido(() -> CheckoutRequest.validarCodigo(7, "abcdef"));
        esperarInvalido(() -> CheckoutRequest.validarCodigo(7, "1234567"));
        esperarInvalido(() -> CheckoutRequest.validarCodigo(7, " "));
        esperarInvalido(() -> CheckoutRequest.reenviarCodigo(0));
        esperarInvalido(() -> CheckoutRequest.iniciarPago(1, "token",
                Collections.singletonList(new CheckoutRequest.Item(1, 1, 10)), "EN_PROCESO"));
    }

    private static void assertKeys(JSONObject object, String... expected) {
        Set<String> actual = new HashSet<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            actual.add(keys.next());
        }
        assertEquals(new HashSet<>(Arrays.asList(expected)), actual);
    }

    private static void esperarInvalido(Runnable construccion) {
        try {
            construccion.run();
            fail("Se esperaba IllegalArgumentException");
        } catch (IllegalArgumentException esperado) {
            // Validación local; no se llega al transporte HTTP.
        }
    }
}
