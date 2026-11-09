package com.incancelables.android;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Estado de presentación del inicio de compra, desacoplado de HttpURLConnection. */
public final class CheckoutFlow {

    private static final String SIMULACION_PAGO = "APROBADO";

    public enum Pantalla {
        FORMULARIO,
        ENVIANDO_PEDIDO,
        INGRESO_CODIGO,
        VALIDANDO_CODIGO,
        REENVIANDO_CODIGO,
        CODIGO_CONFIRMADO,
        PAGO_PROCESANDO,
        PAGO_RECUPERANDO,
        PAGO_LISTO,
        PAGO_RECHAZADO,
        PAGO_ERROR,
        PAGO_CONFIRMADO,
        PAGO_REVISION,
        PAGO_RESULTADO,
        CORRECCION_COMERCIAL,
        ERROR_VALIDACION,
        ERROR,
        RESULTADO_INCIERTO
    }

    public interface Oyente {
        void alCambiarEstado(Estado estado);
    }

    public interface SincronizadorCatalogo {
        void refrescarProductos();
    }

    public static final class Estado {
        private final Pantalla pantalla;
        private final String campoConError;
        private final String mensaje;
        private final CheckoutResponse respuesta;
        private final int pedidoId;
        private final boolean emailEnviado;

        private Estado(Pantalla pantalla, String campoConError, String mensaje,
                       CheckoutResponse respuesta, int pedidoId, boolean emailEnviado) {
            this.pantalla = pantalla;
            this.campoConError = campoConError;
            this.mensaje = mensaje;
            this.respuesta = respuesta;
            this.pedidoId = pedidoId;
            this.emailEnviado = emailEnviado;
        }

        public Pantalla getPantalla() {
            return pantalla;
        }

        public String getCampoConError() {
            return campoConError;
        }

        public String getMensaje() {
            return mensaje;
        }

        public CheckoutResponse getRespuesta() {
            return respuesta;
        }

        public int getPedidoId() {
            return pedidoId;
        }

        public boolean isEmailEnviado() {
            return emailEnviado;
        }
    }

    private static final Pattern EMAIL_BASICO =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private static final Pattern CODIGO_SEIS_DIGITOS = Pattern.compile("^\\d{6}$");

    private final CheckoutSender sender;
    private final Carrito carrito;
    private final SincronizadorCatalogo sincronizadorCatalogo;
    private Estado estado = nuevoEstado(Pantalla.FORMULARIO, null, null, null, 0, false);
    private Oyente oyente;
    private boolean operacionEnCurso;
    private String tokenVerificacion;
    private List<CheckoutRequest.Item> itemsDelPedido = new ArrayList<>();
    private boolean catalogoRefrescadoAlPosponer;

    public CheckoutFlow(CheckoutSender sender, Carrito carrito) {
        this(sender, carrito, new SincronizadorCatalogo() {
            @Override
            public void refrescarProductos() {
                // Sincronizador no-op para consumidores que no administran el catálogo.
            }
        });
    }

    public CheckoutFlow(CheckoutSender sender, Carrito carrito,
                        SincronizadorCatalogo sincronizadorCatalogo) {
        if (sender == null || carrito == null || sincronizadorCatalogo == null) {
            throw new IllegalArgumentException(
                    "CheckoutSender, carrito y sincronizador son obligatorios");
        }
        this.sender = sender;
        this.carrito = carrito;
        this.sincronizadorCatalogo = sincronizadorCatalogo;
    }

    public synchronized void asignarOyente(Oyente nuevoOyente) {
        oyente = nuevoOyente;
        notificar();
    }

    public synchronized void quitarOyente(Oyente anterior) {
        if (oyente == anterior) {
            oyente = null;
        }
    }

    public synchronized Estado getEstado() {
        return estado;
    }

    public synchronized boolean isOperacionEnCurso() {
        return operacionEnCurso;
    }

    /**
     * Token de autorización en memoria; nunca se muestra ni se registra.
     * Solo existe mientras el flujo de este proceso siga vivo.
     */
    public synchronized String obtenerTokenVerificacion() {
        return tokenVerificacion;
    }

    public synchronized void realizarPedido(String nombre, String apellido, String email) {
        if (operacionEnCurso || estado.pantalla == Pantalla.INGRESO_CODIGO
                || estado.pantalla == Pantalla.CODIGO_CONFIRMADO
                || estado.pantalla == Pantalla.PAGO_PROCESANDO
                || estado.pantalla == Pantalla.PAGO_RECUPERANDO
                || estado.pantalla == Pantalla.PAGO_LISTO
                || estado.pantalla == Pantalla.PAGO_RECHAZADO
                || estado.pantalla == Pantalla.PAGO_ERROR
                || estado.pantalla == Pantalla.PAGO_CONFIRMADO
                || estado.pantalla == Pantalla.PAGO_REVISION
                || estado.pantalla == Pantalla.PAGO_RESULTADO
                || estado.pantalla == Pantalla.CORRECCION_COMERCIAL
                || estado.pantalla == Pantalla.RESULTADO_INCIERTO) {
            return;
        }

        Estado error = validarFormulario(nombre, apellido, email);
        if (error != null) {
            estado = error;
            notificar();
            return;
        }

        List<CheckoutRequest.Item> lineas = lineasDelCarrito();
        final CheckoutRequest solicitud;
        try {
            solicitud = CheckoutRequest.crearPedido(email, nombre, apellido, lineas);
        } catch (IllegalArgumentException e) {
            estado = nuevoEstado(Pantalla.ERROR_VALIDACION, null,
                    "Revisá los datos ingresados y el contenido del carrito.", null, 0, false);
            notificar();
            return;
        }

        operacionEnCurso = true;
        estado = nuevoEstado(Pantalla.ENVIANDO_PEDIDO, null,
                "Enviando el pedido…", null, 0, false);
        notificar();
        enviar(solicitud, new CheckoutSender.Callback() {
            @Override
            public void alCompletar(CheckoutResponse respuesta) {
                procesarCreacion(respuesta);
            }
        });
    }

    /** Valida el código de seis dígitos contra el backend. Solo desde ingreso de código. */
    public synchronized void validarCodigo(String codigo) {
        if (operacionEnCurso || estado.pantalla != Pantalla.INGRESO_CODIGO
                || estado.pedidoId <= 0) {
            return;
        }

        String codigoNormalizado = codigo == null ? "" : codigo.trim();
        if (!CODIGO_SEIS_DIGITOS.matcher(codigoNormalizado).matches()) {
            estado = nuevoEstado(Pantalla.INGRESO_CODIGO, "codigo",
                    "Ingresá el código de 6 dígitos.", estado.respuesta,
                    estado.pedidoId, estado.emailEnviado);
            notificar();
            return;
        }

        final CheckoutRequest solicitud = CheckoutRequest.validarCodigo(
                estado.pedidoId, codigoNormalizado);
        operacionEnCurso = true;
        estado = nuevoEstado(Pantalla.VALIDANDO_CODIGO, null,
                "Validando el código…", estado.respuesta,
                estado.pedidoId, estado.emailEnviado);
        notificar();
        enviar(solicitud, new CheckoutSender.Callback() {
            @Override
            public void alCompletar(CheckoutResponse respuesta) {
                procesarValidacion(respuesta);
            }
        });
    }

    /** Solicita un código nuevo vía /email-verificaciones/codigo. Solo desde ingreso. */
    public synchronized void reenviarCodigo() {
        if (operacionEnCurso || estado.pantalla != Pantalla.INGRESO_CODIGO
                || estado.pedidoId <= 0) {
            return;
        }

        final CheckoutRequest solicitud = CheckoutRequest.reenviarCodigo(estado.pedidoId);
        operacionEnCurso = true;
        estado = nuevoEstado(Pantalla.REENVIANDO_CODIGO, null,
                "Solicitando un código nuevo…", estado.respuesta,
                estado.pedidoId, estado.emailEnviado);
        notificar();
        enviar(solicitud, new CheckoutSender.Callback() {
            @Override
            public void alCompletar(CheckoutResponse respuesta) {
                procesarReenvioCodigo(respuesta);
            }
        });
    }

    /** Deja visible el catálogo sin perder el pedido pendiente ni su disponibilidad. */
    public synchronized void posponerVerificacion() {
        if (estado.pantalla != Pantalla.INGRESO_CODIGO || estado.pedidoId <= 0
                || catalogoRefrescadoAlPosponer) {
            return;
        }
        Api.definirCompromisoPedido(lineasDelCarrito());
        catalogoRefrescadoAlPosponer = true;
        sincronizadorCatalogo.refrescarProductos();
    }

    /** Aplica solo tras una acción explícita del comprador; no vuelve a enviar el pedido. */
    public synchronized boolean aplicarCorreccionAlCarrito() {
        if (estado.pantalla != Pantalla.CORRECCION_COMERCIAL || estado.respuesta == null
                || estado.respuesta.getDatos() == null) {
            return false;
        }

        JSONObject datos = estado.respuesta.getDatos();
        JSONArray corregidos = datos.optJSONArray("carrito_corregido");
        JSONArray disponibilidades = datos.optJSONArray("disponibilidad");
        if (corregidos == null) {
            return false;
        }

        Map<Integer, Carrito.Item> anteriores = new HashMap<>();
        for (Carrito.Item item : carrito.getItems()) {
            anteriores.put(item.getProducto().getId(), item);
        }
        Map<Integer, Integer> stockPorId = new HashMap<>();
        if (disponibilidades != null) {
            for (int i = 0; i < disponibilidades.length(); i++) {
                JSONObject disponibilidad = disponibilidades.optJSONObject(i);
                if (disponibilidad != null && disponibilidad.has("producto_id")
                        && disponibilidad.has("disponibilidad")) {
                    stockPorId.put(disponibilidad.optInt("producto_id"),
                            disponibilidad.optInt("disponibilidad"));
                }
            }
        }

        List<Carrito.Item> nuevos = new ArrayList<>();
        for (int i = 0; i < corregidos.length(); i++) {
            JSONObject linea = corregidos.optJSONObject(i);
            if (linea == null) {
                return false;
            }
            int id = linea.optInt("producto_id", -1);
            int cantidad = linea.optInt("cantidad", 0);
            Carrito.Item anterior = anteriores.get(id);
            if (anterior == null || cantidad <= 0 || !linea.has("precio_unitario")) {
                return false;
            }
            double precio = linea.optDouble("precio_unitario", Double.NaN);
            int disponibilidad = stockPorId.containsKey(id)
                    ? stockPorId.get(id)
                    : anterior.getProducto().getDisponibilidad();
            if (Double.isNaN(precio) || Double.isInfinite(precio)
                    || cantidad > disponibilidad) {
                return false;
            }

            Producto productoAnterior = anterior.getProducto();
            Producto actualizado = new Producto(id, productoAnterior.getNombre(),
                    productoAnterior.getDescripcion(), precio, disponibilidad,
                    productoAnterior.getImagenes());
            nuevos.add(new Carrito.Item(actualizado, cantidad));
        }

        if (!carrito.reemplazar(nuevos)) {
            return false;
        }
        sincronizadorCatalogo.refrescarProductos();
        if (estado.pedidoId > 0 && tokenVerificacion != null) {
            estado = nuevoEstado(Pantalla.PAGO_LISTO, null,
                    "Se aplicaron los cambios. Revisá el carrito y continuá al pago.",
                    estado.respuesta, estado.pedidoId, estado.emailEnviado);
        } else {
            estado = nuevoEstado(Pantalla.FORMULARIO, null,
                    "Se aplicaron los cambios sugeridos. Revisá el resumen antes de volver a enviar.",
                    null, 0, false);
        }
        notificar();
        return true;
    }

    /** Reintenta el pago solo desde un estado en que el contrato lo permite. */
    public synchronized void reintentarPago() {
        if (operacionEnCurso || tokenVerificacion == null || estado.pedidoId <= 0
                || (estado.pantalla != Pantalla.PAGO_LISTO
                && estado.pantalla != Pantalla.PAGO_RECHAZADO
                && estado.pantalla != Pantalla.PAGO_ERROR)) {
            return;
        }
        procesarPagoActual();
    }

    /**
     * Restaura el flujo tras la muerte del proceso conservando el pedido creado.
     * Solo aplica a las pantallas de verificación por código; una operación
     * interrumpida vuelve a ingreso de código para no duplicar pedidos.
     */
    public synchronized void restaurar(int pedidoId, String pantalla, boolean emailEnviado,
                                       String mensaje) {
        if (operacionEnCurso || pedidoId <= 0 || pantalla == null
                || estado.pantalla != Pantalla.FORMULARIO) {
            return;
        }

        Pantalla destino;
        try {
            destino = Pantalla.valueOf(pantalla);
        } catch (IllegalArgumentException e) {
            return;
        }

        String mensajeRestaurado = mensaje;
        if (destino == Pantalla.VALIDANDO_CODIGO || destino == Pantalla.REENVIANDO_CODIGO) {
            destino = Pantalla.INGRESO_CODIGO;
            mensajeRestaurado = "Se interrumpió la verificación. Ingresá el código "
                    + "o solicitá uno nuevo.";
        }
        if (destino == Pantalla.PAGO_PROCESANDO || destino == Pantalla.PAGO_RECUPERANDO
                || destino == Pantalla.PAGO_LISTO
                || destino == Pantalla.CODIGO_CONFIRMADO) {
            destino = Pantalla.PAGO_RESULTADO;
            mensajeRestaurado = "No se pudo confirmar el resultado del pago. "
                    + "Revisá el estado del pedido antes de volver a intentarlo.";
        } else if (destino == Pantalla.PAGO_RECHAZADO || destino == Pantalla.PAGO_ERROR) {
            destino = Pantalla.PAGO_RESULTADO;
            mensajeRestaurado = "La sesión de pago se interrumpió. No se inició otro intento.";
        }
        if (destino != Pantalla.INGRESO_CODIGO && destino != Pantalla.PAGO_CONFIRMADO
                && destino != Pantalla.PAGO_REVISION && destino != Pantalla.PAGO_RESULTADO) {
            return;
        }

        estado = nuevoEstado(destino, null, mensajeRestaurado, null, pedidoId, emailEnviado);
        notificar();
    }

    public synchronized void cerrar() {
        tokenVerificacion = null;
        itemsDelPedido.clear();
        if (sender instanceof AutoCloseable) {
            try {
                ((AutoCloseable) sender).close();
            } catch (Exception ignored) {
                // Cerrar el ejecutor no cambia el resultado de una operación ya enviada.
            }
        }
    }

    private Estado validarFormulario(String nombre, String apellido, String email) {
        if (nombre == null || nombre.trim().isEmpty()
                || nombre.trim().codePointCount(0, nombre.trim().length()) > 100) {
            return nuevoEstado(Pantalla.ERROR_VALIDACION, "nombre",
                    "Ingresá un nombre de hasta 100 caracteres.", null, 0, false);
        }
        if (apellido == null || apellido.trim().isEmpty()
                || apellido.trim().codePointCount(0, apellido.trim().length()) > 100) {
            return nuevoEstado(Pantalla.ERROR_VALIDACION, "apellido",
                    "Ingresá un apellido de hasta 100 caracteres.", null, 0, false);
        }
        if (email == null || !EMAIL_BASICO.matcher(email.trim()).matches()) {
            return nuevoEstado(Pantalla.ERROR_VALIDACION, "email",
                    "Ingresá un email válido.", null, 0, false);
        }
        if (carrito.getItems().isEmpty()) {
            return nuevoEstado(Pantalla.ERROR_VALIDACION, null,
                    "El carrito está vacío.", null, 0, false);
        }
        return null;
    }

    private List<CheckoutRequest.Item> lineasDelCarrito() {
        List<CheckoutRequest.Item> lineas = new ArrayList<>();
        for (Carrito.Item item : carrito.getItems()) {
            Producto producto = item.getProducto();
            lineas.add(new CheckoutRequest.Item(producto.getId(), item.getCantidad(),
                    producto.getPrecio()));
        }
        return lineas;
    }

    private void procesarCreacion(CheckoutResponse respuesta) {
        synchronized (this) {
            operacionEnCurso = false;
            if (respuesta.getEstado() == CheckoutResponse.Estado.PEDIDO_CREADO) {
                JSONObject pedido = respuesta.getDatos().optJSONObject("pedido");
                int id = pedido.optInt("id", 0);
                boolean enviado = respuesta.getDatos().optBoolean("email_enviado", false);
                catalogoRefrescadoAlPosponer = false;
                estado = nuevoEstado(Pantalla.INGRESO_CODIGO, null, null,
                        respuesta, id, enviado);
            } else if (respuesta.getEstado() == CheckoutResponse.Estado.CORRECCION_COMERCIAL) {
                estado = nuevoEstado(Pantalla.CORRECCION_COMERCIAL, null,
                        "El pedido no se creó porque cambiaron el precio o la disponibilidad. "
                                + "Revisá la corrección antes de volver a intentar.",
                        respuesta, 0, false);
            } else if (respuesta.getEstado() == CheckoutResponse.Estado.RESULTADO_INCIERTO
                    || respuesta.requiereReconciliacion()) {
                estado = nuevoEstado(Pantalla.RESULTADO_INCIERTO, null,
                        "No se pudo confirmar si el pedido fue creado. Revisá tu email antes "
                                + "de intentar otra vez. No repetimos el envío.",
                        respuesta, 0, false);
            } else {
                String error = respuesta.getCodigoError();
                String campo = campoDeError(error);
                estado = nuevoEstado(campo == null ? Pantalla.ERROR : Pantalla.ERROR_VALIDACION,
                        campo, mensajeError(error), respuesta, 0, false);
            }
            notificar();
        }
    }

    private void procesarValidacion(CheckoutResponse respuesta) {
        synchronized (this) {
            operacionEnCurso = false;
            int pedidoId = estado.pedidoId;
            boolean emailEnviado = estado.emailEnviado;
            CheckoutResponse.Estado estadoRespuesta = respuesta.getEstado();

            if (estadoRespuesta == CheckoutResponse.Estado.CODIGO_CONFIRMADO) {
                JSONObject datos = respuesta.getDatos();
                tokenVerificacion = datos == null ? null : datos.optString("token", null);
                if (tokenVerificacion == null || tokenVerificacion.isEmpty()) {
                    estado = nuevoEstado(Pantalla.PAGO_RESULTADO, null,
                            "No se pudo identificar la autorización para iniciar el pago.",
                            respuesta, pedidoId, emailEnviado);
                } else {
                    recuperarPedidoVerificado(pedidoId, emailEnviado);
                    return;
                }
                notificar();
                return;
            } else if (estadoRespuesta == CheckoutResponse.Estado.RESULTADO_INCIERTO
                    || respuesta.requiereReconciliacion()) {
                estado = nuevoEstado(Pantalla.INGRESO_CODIGO, null,
                        "No se pudo confirmar la validación. Intentá nuevamente "
                                + "con el mismo código.",
                        respuesta, pedidoId, emailEnviado);
            } else {
                String codigo = respuesta.getCodigoError();
                if (esErrorFatalDeVerificacion(codigo)) {
                    Api.limpiarCompromisoPedido();
                    estado = nuevoEstado(Pantalla.ERROR, null,
                            mensajeDeErrorCodigo(codigo), respuesta, 0, false);
                } else {
                    estado = nuevoEstado(Pantalla.INGRESO_CODIGO, null,
                            mensajeDeErrorCodigo(codigo), respuesta, pedidoId, emailEnviado);
                }
            }
            notificar();
        }
    }

    private void procesarReenvioCodigo(CheckoutResponse respuesta) {
        synchronized (this) {
            operacionEnCurso = false;
            int pedidoId = estado.pedidoId;
            boolean emailEnviado = estado.emailEnviado;
            CheckoutResponse.Estado estadoRespuesta = respuesta.getEstado();

            if (estadoRespuesta == CheckoutResponse.Estado.CODIGO_REENVIADO) {
                estado = nuevoEstado(Pantalla.INGRESO_CODIGO, null,
                        "Se envió un código nuevo a tu email.",
                        respuesta, pedidoId, true);
            } else if (estadoRespuesta == CheckoutResponse.Estado.COOLDOWN_REENVIO) {
                int segundos = respuesta.getDatos() == null ? 0
                        : respuesta.getDatos().optInt("segundos_restantes", 0);
                estado = nuevoEstado(Pantalla.INGRESO_CODIGO, null,
                        segundos > 0
                                ? "Esperá " + segundos + " segundos antes de solicitar otro código."
                                : "Todavía no se puede reenviar el código.",
                        respuesta, pedidoId, emailEnviado);
            } else if ("error_envio_email".equals(respuesta.getCodigoError())) {
                estado = nuevoEstado(Pantalla.INGRESO_CODIGO, null,
                        "No se pudo enviar el código. Intentá nuevamente.",
                        respuesta, pedidoId, emailEnviado);
            } else if (estadoRespuesta == CheckoutResponse.Estado.RESULTADO_INCIERTO
                    || respuesta.requiereReconciliacion()) {
                estado = nuevoEstado(Pantalla.INGRESO_CODIGO, null,
                        "No se pudo confirmar el reenvío. Revisá tu correo antes "
                                + "de volver a solicitarlo.",
                        respuesta, pedidoId, emailEnviado);
            } else {
                String codigo = respuesta.getCodigoError();
                if (esErrorFatalDeVerificacion(codigo)) {
                    Api.limpiarCompromisoPedido();
                    estado = nuevoEstado(Pantalla.ERROR, null,
                            mensajeDeErrorCodigo(codigo), respuesta, 0, false);
                } else {
                    estado = nuevoEstado(Pantalla.INGRESO_CODIGO, null,
                            mensajeDeErrorCodigo(codigo), respuesta, pedidoId, emailEnviado);
                }
            }
            notificar();
        }
    }

    private void procesarPagoActual() {
        if (operacionEnCurso || tokenVerificacion == null || estado.pedidoId <= 0) {
            return;
        }
        List<CheckoutRequest.Item> lineas = lineasDelCarrito();
        if (lineas.isEmpty()) {
            estado = nuevoEstado(Pantalla.PAGO_RESULTADO, null,
                    "Tu carrito está vacío. No se pudo iniciar el pago.",
                    null, estado.pedidoId, estado.emailEnviado);
            notificar();
            return;
        }

        operacionEnCurso = true;
        estado = nuevoEstado(Pantalla.PAGO_PROCESANDO, null,
                "Estamos procesando tu pago. Esperá la respuesta del servidor.",
                null, estado.pedidoId, estado.emailEnviado);
        notificar();

        if (!lineasIguales(lineas, itemsDelPedido)) {
            final CheckoutRequest reemplazo;
            try {
                reemplazo = CheckoutRequest.reemplazarPedido(tokenVerificacion, lineas);
            } catch (IllegalArgumentException e) {
                operacionEnCurso = false;
                procesarErrorPago("No se pudo preparar el pedido actualizado.", false, null);
                return;
            }
            enviar(reemplazo, new CheckoutSender.Callback() {
                @Override
                public void alCompletar(CheckoutResponse respuesta) {
                    procesarReemplazoParaPago(respuesta, lineas);
                }
            });
        } else {
            enviarInicioPago(estado.pedidoId, lineas);
        }
    }

    private void recuperarPedidoVerificado(int pedidoId, boolean emailEnviado) {
        final CheckoutRequest solicitud;
        try {
            solicitud = CheckoutRequest.obtenerPedidosVerificados(tokenVerificacion);
        } catch (IllegalArgumentException e) {
            estado = nuevoEstado(Pantalla.PAGO_RESULTADO, null,
                    "No pudimos recuperar tu compra. Intentá nuevamente.",
                    null, pedidoId, emailEnviado);
            notificar();
            return;
        }
        operacionEnCurso = true;
        estado = nuevoEstado(Pantalla.PAGO_RECUPERANDO, null,
                "Estamos recuperando tu pedido verificado…", null, pedidoId, emailEnviado);
        notificar();
        enviar(solicitud, new CheckoutSender.Callback() {
            @Override
            public void alCompletar(CheckoutResponse respuesta) {
                procesarRecuperacionPedido(respuesta, pedidoId, emailEnviado);
            }
        });
    }

    private void procesarRecuperacionPedido(CheckoutResponse respuesta, int pedidoId,
                                            boolean emailEnviado) {
        synchronized (this) {
            operacionEnCurso = false;
            if (respuesta.getEstado() != CheckoutResponse.Estado.PEDIDOS_RECUPERADOS
                    || respuesta.getDatos() == null) {
                Api.limpiarCompromisoPedido();
                estado = nuevoEstado(Pantalla.PAGO_RESULTADO, null,
                        "No pudimos recuperar tu compra. Intentá nuevamente.",
                        respuesta, pedidoId, emailEnviado);
                notificar();
                return;
            }

            JSONArray pedidos = respuesta.getDatos().optJSONArray("pedidos");
            JSONObject seleccionado = null;
            if (pedidos != null) {
                for (int i = 0; i < pedidos.length(); i++) {
                    JSONObject pedido = pedidos.optJSONObject(i);
                    if (pedido != null && "PEND_PAGO".equals(pedido.optString("estado"))) {
                        seleccionado = pedido;
                        break;
                    }
                }
            }
            if (seleccionado == null || !aplicarPedidoRecuperado(seleccionado)) {
                Api.limpiarCompromisoPedido();
                estado = nuevoEstado(Pantalla.PAGO_RESULTADO, null,
                        "No hay compras pendientes de pago.", respuesta,
                        pedidoId, emailEnviado);
                notificar();
                return;
            }

            int pedidoRecuperadoId = seleccionado.optInt("id", pedidoId);
            if (pedidoRecuperadoId <= 0) pedidoRecuperadoId = pedidoId;
            Api.definirCompromisoPedido(itemsDelPedido);
            sincronizadorCatalogo.refrescarProductos();
            estado = nuevoEstado(Pantalla.PAGO_LISTO, null,
                    "Tu pedido está listo para pagar.", respuesta,
                    pedidoRecuperadoId, emailEnviado);
            notificar();
        }
    }

    private boolean aplicarPedidoRecuperado(JSONObject pedido) {
        JSONArray items = pedido.optJSONArray("items");
        if (items == null || items.length() == 0) return false;

        Map<Integer, Carrito.Item> actuales = new HashMap<>();
        for (Carrito.Item item : carrito.getItems()) {
            actuales.put(item.getProducto().getId(), item);
        }
        List<Carrito.Item> nuevoCarrito = new ArrayList<>();
        List<CheckoutRequest.Item> lineas = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject linea = items.optJSONObject(i);
            if (linea == null) return false;
            int productoId = linea.optInt("producto_id", 0);
            int cantidad = linea.optInt("cantidad", 0);
            double precio = linea.optDouble("precio_unitario", Double.NaN);
            if (productoId <= 0 || cantidad <= 0 || Double.isNaN(precio)
                    || Double.isInfinite(precio) || precio < 0) {
                return false;
            }

            Carrito.Item anterior = actuales.get(productoId);
            Producto producto;
            if (anterior == null) {
                producto = new Producto(productoId, "Producto #" + productoId,
                        "", precio, cantidad);
            } else {
                Producto productoAnterior = anterior.getProducto();
                producto = new Producto(productoId, productoAnterior.getNombre(),
                        productoAnterior.getDescripcion(), precio,
                        Math.max(cantidad, productoAnterior.getDisponibilidad()),
                        productoAnterior.getImagenes());
            }
            nuevoCarrito.add(new Carrito.Item(producto, cantidad));
            lineas.add(new CheckoutRequest.Item(productoId, cantidad, precio));
        }
        if (!carrito.reemplazar(nuevoCarrito)) return false;
        itemsDelPedido = copiarLineas(lineas);
        return true;
    }

    private void procesarReemplazoParaPago(CheckoutResponse respuesta,
                                           List<CheckoutRequest.Item> lineas) {
        synchronized (this) {
            if (respuesta.getEstado() == CheckoutResponse.Estado.PEDIDO_REEMPLAZADO) {
                JSONObject pedidoNuevo = respuesta.getDatos().optJSONObject("pedido");
                int nuevoId = pedidoNuevo == null ? 0 : pedidoNuevo.optInt("id", 0);
                if (nuevoId <= 0) {
                    operacionEnCurso = false;
                    procesarErrorPago("Respuesta inesperada al actualizar el pedido.", false,
                            respuesta);
                    return;
                }
                itemsDelPedido = copiarLineas(lineas);
                Api.definirCompromisoPedido(itemsDelPedido);
                estado = nuevoEstado(Pantalla.PAGO_PROCESANDO, null,
                        "El pedido fue actualizado. Iniciando el pago…",
                        respuesta, nuevoId, estado.emailEnviado);
                enviarInicioPago(nuevoId, lineas);
            } else if (respuesta.getEstado() == CheckoutResponse.Estado.CORRECCION_COMERCIAL) {
                operacionEnCurso = false;
                estado = nuevoEstado(Pantalla.CORRECCION_COMERCIAL, null,
                        "Revisá los cambios del pedido antes de volver a intentar el pago.",
                        respuesta, estado.pedidoId, estado.emailEnviado);
                notificar();
            } else {
                procesarResultadoErrorPago(respuesta);
            }
        }
    }

    private void enviarInicioPago(int pedidoId, List<CheckoutRequest.Item> lineas) {
        final CheckoutRequest pago;
        try {
            pago = CheckoutRequest.iniciarPago(pedidoId, tokenVerificacion, lineas,
                    SIMULACION_PAGO);
        } catch (IllegalArgumentException e) {
            operacionEnCurso = false;
            procesarErrorPago("No se pudo preparar la solicitud de pago.", true, null);
            return;
        }
        enviar(pago, new CheckoutSender.Callback() {
            @Override
            public void alCompletar(CheckoutResponse respuesta) {
                procesarResultadoPago(respuesta);
            }
        });
    }

    private void procesarResultadoPago(CheckoutResponse respuesta) {
        synchronized (this) {
            operacionEnCurso = false;
            int pedidoId = estado.pedidoId;
            boolean emailEnviado = estado.emailEnviado;
            switch (respuesta.getEstado()) {
                case PAGO_CONFIRMADO:
                    carrito.vaciar();
                    Api.limpiarCompromisoPedido();
                    sincronizadorCatalogo.refrescarProductos();
                    estado = nuevoEstado(Pantalla.PAGO_CONFIRMADO, null,
                            mensajePagoAprobado(respuesta), respuesta, pedidoId, emailEnviado);
                    break;
                case PAGO_REQUIERE_REVISION:
                    Api.limpiarCompromisoPedido();
                    estado = nuevoEstado(Pantalla.PAGO_REVISION, null,
                            "El pago fue registrado, pero la compra requiere revisión.",
                            respuesta, pedidoId, emailEnviado);
                    break;
                case PAGO_RECHAZADO: {
                    JSONObject datos = respuesta.getDatos();
                    JSONObject pedido = datos == null ? null : datos.optJSONObject("pedido");
                    int intentos = datos == null ? 0 : datos.optInt("intentos", 0);
                    int maximo = datos == null ? 0 : datos.optInt("maximo_intentos", 0);
                    boolean reintentoDisponible = pedido != null
                            && "PEND_PAGO".equals(pedido.optString("estado"))
                            && intentos > 0 && maximo > intentos;
                    if (!reintentoDisponible) Api.limpiarCompromisoPedido();
                    estado = nuevoEstado(reintentoDisponible
                                    ? Pantalla.PAGO_RECHAZADO : Pantalla.PAGO_RESULTADO,
                            null, mensajePagoRechazado(intentos, maximo, reintentoDisponible),
                            respuesta, pedidoId, emailEnviado);
                    break;
                }
                case PAGO_EN_PROCESO:
                    estado = nuevoEstado(Pantalla.PAGO_ERROR, null,
                            "Ocurrió un error al procesar tu pago. Intentá nuevamente.",
                            respuesta, pedidoId, emailEnviado);
                    break;
                case CORRECCION_COMERCIAL:
                    estado = nuevoEstado(Pantalla.CORRECCION_COMERCIAL, null,
                            "El pago no se realizó. Revisá los cambios sugeridos y volvé a intentar.",
                            respuesta, pedidoId, emailEnviado);
                    break;
                default:
                    procesarResultadoErrorPago(respuesta);
                    return;
            }
            notificar();
        }
    }

    private void procesarResultadoErrorPago(CheckoutResponse respuesta) {
        operacionEnCurso = false;
        String codigo = respuesta.getCodigoError();
        boolean terminal = esErrorTerminalPago(codigo);
        if (terminal) Api.limpiarCompromisoPedido();
        String mensaje = mensajeErrorPago(codigo, respuesta);
        procesarErrorPago(mensaje, !terminal, respuesta);
    }

    private void procesarErrorPago(String mensaje, boolean reintentoDisponible,
                                   CheckoutResponse respuesta) {
        int pedidoId = estado.pedidoId;
        boolean emailEnviado = estado.emailEnviado;
        estado = nuevoEstado(reintentoDisponible ? Pantalla.PAGO_ERROR : Pantalla.PAGO_RESULTADO,
                null, mensaje, respuesta, pedidoId, emailEnviado);
        notificar();
    }

    private static boolean esErrorTerminalPago(String codigo) {
        if (codigo == null) return false;
        switch (codigo) {
            case "token_invalido":
            case "token_expirado":
            case "verificacion_pendiente":
            case "pedido_vencido":
            case "maximo_intentos_alcanzado":
            case "pago_ya_aprobado":
            case "pedido_inexistente":
            case "pedido_no_activo":
            case "pedido_no_pendiente_de_pago":
            case "varios_pedidos_activos":
                return true;
            default:
                return false;
        }
    }

    private static String mensajeErrorPago(String codigo, CheckoutResponse respuesta) {
        switch (codigo == null ? "" : codigo) {
            case "token_invalido":
                return "La verificación del email no está disponible.";
            case "token_expirado":
                return "La verificación del email expiró.";
            case "verificacion_pendiente":
                return "Tu email todavía no está verificado. Revisá tu correo para continuar.";
            case "pedido_vencido":
                return "Este pedido ya no está disponible para pagar porque venció.";
            case "maximo_intentos_alcanzado":
                return "Se alcanzó el máximo de intentos de pago de este pedido.";
            case "pago_ya_aprobado":
                return "El pago de este pedido ya fue aprobado anteriormente.";
            case "pedido_inexistente":
            case "pedido_no_activo":
            case "pedido_no_pendiente_de_pago":
                return "Este pedido no está disponible para pagar.";
            case "varios_pedidos_activos":
                return "Hay varios pedidos activos con esta verificación. Escribinos para continuar.";
            default:
                if (respuesta != null && respuesta.getDatos() == null
                        && respuesta.getCodigoHttp() < 0) {
                    return "No se pudo confirmar el resultado del pago. Podés volver a intentarlo.";
                }
                return "Ocurrió un error al procesar tu pago. Intentá nuevamente.";
        }
    }

    private static String mensajePagoAprobado(CheckoutResponse respuesta) {
        JSONObject datos = respuesta.getDatos();
        JSONObject pago = datos == null ? null : datos.optJSONObject("pago");
        JSONObject validacion = datos == null ? null : datos.optJSONObject("validacion");
        StringBuilder mensaje = new StringBuilder("Tu compra fue confirmada correctamente.");
        if (pago != null) {
            double importe = pago.optDouble("importe", 0);
            if (importe > 0) {
                mensaje.append(" Importe: ").append(Producto.formatearPrecio(importe)).append('.');
            }
        }
        if (validacion != null) {
            String referencia = validacion.optString("referencia_proveedor", "");
            if (!referencia.isEmpty()) {
                mensaje.append(" Referencia: ").append(referencia).append('.');
            }
        }
        return mensaje.toString();
    }

    private static String mensajePagoRechazado(int intentos, int maximo,
                                               boolean reintentoDisponible) {
        String mensaje = "Tu pago fue rechazado.";
        if (intentos > 0 && maximo > 0) {
            mensaje += " Intento " + intentos + " de " + maximo + ".";
        }
        return mensaje + (reintentoDisponible
                ? " Podés volver a intentarlo." : " No quedan intentos disponibles.");
    }

    private static List<CheckoutRequest.Item> copiarLineas(List<CheckoutRequest.Item> items) {
        return new ArrayList<>(items);
    }

    private static boolean lineasIguales(List<CheckoutRequest.Item> actuales,
                                         List<CheckoutRequest.Item> snapshot) {
        if (actuales == null || snapshot == null || actuales.size() != snapshot.size()) {
            return false;
        }
        for (CheckoutRequest.Item actual : actuales) {
            boolean encontrado = false;
            for (CheckoutRequest.Item anterior : snapshot) {
                if (actual.getProductoId() == anterior.getProductoId()
                        && actual.getCantidad() == anterior.getCantidad()
                        && Double.compare(actual.getPrecioUnitario(),
                        anterior.getPrecioUnitario()) == 0) {
                    encontrado = true;
                    break;
                }
            }
            if (!encontrado) return false;
        }
        return true;
    }

    private void enviar(CheckoutRequest solicitud, CheckoutSender.Callback callback) {
        try {
            sender.enviar(solicitud, callback);
        } catch (RuntimeException e) {
            callback.alCompletar(CheckoutResponse.errorDeRed(solicitud));
        }
    }

    private static String campoDeError(String codigo) {
        if ("Nombre inválido".equals(codigo)) return "nombre";
        if ("Apellido inválido".equals(codigo)) return "apellido";
        if ("Email inválido".equals(codigo)) return "email";
        return null;
    }

    private static String mensajeError(String codigo) {
        if (codigo == null || codigo.trim().isEmpty()) {
            return "No fue posible procesar el pedido. Intentá nuevamente.";
        }
        switch (codigo) {
            case "El carrito está vacío":
                return "El carrito está vacío.";
            case "Cuerpo JSON inválido":
            case "Falta el campo email":
            case "Falta el campo nombre":
            case "Falta el campo apellido":
            case "Falta el campo items":
                return "Faltan datos del pedido. Revisá el formulario y el carrito.";
            default:
                return codigo;
        }
    }

    /** Errores que dejan al pedido sin posibilidad de continuar la verificación. */
    private static boolean esErrorFatalDeVerificacion(String codigo) {
        if (codigo == null) {
            return false;
        }
        switch (codigo) {
            case "pedido_inexistente":
            case "verificacion_no_iniciada":
            case "verificacion_ya_verificada":
            case "no_encontrada":
            case "pedido_vencido":
            case "pedido_no_pendiente_de_verificacion":
                return true;
            default:
                return false;
        }
    }

    private static String mensajeDeErrorCodigo(String codigo) {
        if (codigo == null || codigo.trim().isEmpty()) {
            return "No se pudo completar la verificación. Intentá nuevamente.";
        }
        switch (codigo) {
            case "codigo_invalido":
                return "El código es incorrecto. Revisá tu email.";
            case "codigo_expirado":
                return "El código expiró. Solicitá uno nuevo.";
            case "maximo_intentos_alcanzado":
                return "Se agotaron los intentos. Solicitá un código nuevo.";
            case "verificacion_ya_verificada":
                return "Esta verificación ya fue completada.";
            case "pedido_vencido":
                return "El pedido expiró. Volvé a iniciar la compra.";
            case "pedido_inexistente":
            case "verificacion_no_iniciada":
            case "no_encontrada":
            case "pedido_no_pendiente_de_verificacion":
                return "El pedido ya no está disponible para verificarlo. "
                        + "Volvé a iniciar la compra.";
            case "error_envio_email":
                return "No se pudo enviar el código. Intentá nuevamente.";
            default:
                return codigo;
        }
    }

    private static Estado nuevoEstado(Pantalla pantalla, String campo, String mensaje,
                                      CheckoutResponse respuesta, int pedidoId,
                                      boolean emailEnviado) {
        return new Estado(pantalla, campo, mensaje, respuesta, pedidoId, emailEnviado);
    }

    private void notificar() {
        if (oyente != null) {
            oyente.alCambiarEstado(estado);
        }
    }

}
