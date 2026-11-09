package com.incancelables.android;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Formulario y estados iniciales del checkout; no contiene transporte HTTP. */
public class CheckoutActivity extends AppCompatActivity implements CheckoutFlow.Oyente {

    private static CheckoutFlow flujoDelProceso;

    private static final String ESTADO_PEDIDO_ID = "checkout_pedido_id";
    private static final String ESTADO_PANTALLA = "checkout_pantalla";
    private static final String ESTADO_EMAIL_ENVIADO = "checkout_email_enviado";
    private static final String ESTADO_MENSAJE = "checkout_mensaje";

    private CheckoutFlow flow;
    private EditText nombre;
    private EditText apellido;
    private EditText email;
    private TextView resumen;
    private LinearLayout formulario;
    private LinearLayout resultado;
    private LinearLayout codigoGrupo;
    private EditText codigo;
    private TextView mensajeFormulario;
    private TextView errorNombre;
    private TextView errorApellido;
    private TextView errorEmail;
    private TextView tituloResultado;
    private TextView mensajeResultado;
    private TextView detalleCorreccion;
    private Button realizarPedido;
    private Button aplicarCorreccion;
    private Button reenviarEmail;
    private Button validarCodigo;
    private Button pagar;
    private Button finalizar;
    private Button volverCarrito;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_checkout);
        findViewById(R.id.checkout_root).requestFocus();

        Toolbar toolbar = findViewById(R.id.checkout_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.checkout_titulo);
        }

        enlazarVistas();
        configurarAcciones();

        Object retenido = getLastCustomNonConfigurationInstance();
        if (retenido instanceof CheckoutFlow) {
            flow = (CheckoutFlow) retenido;
        } else if (flujoDelProceso != null) {
            flow = flujoDelProceso;
        } else {
            flow = new CheckoutFlow(new CheckoutApi(getApplicationContext()),
                    Carrito.obtenerInstancia(), Api::refrescar);
            flujoDelProceso = flow;
            if (savedInstanceState != null) {
                flow.restaurar(savedInstanceState.getInt(ESTADO_PEDIDO_ID, 0),
                        savedInstanceState.getString(ESTADO_PANTALLA),
                        savedInstanceState.getBoolean(ESTADO_EMAIL_ENVIADO, false),
                        savedInstanceState.getString(ESTADO_MENSAJE));
            }
        }
        flow.asignarOyente(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (flow != null) {
            CheckoutFlow.Estado estado = flow.getEstado();
            outState.putInt(ESTADO_PEDIDO_ID, estado.getPedidoId());
            outState.putString(ESTADO_PANTALLA, estado.getPantalla().name());
            outState.putBoolean(ESTADO_EMAIL_ENVIADO, estado.isEmailEnviado());
            outState.putString(ESTADO_MENSAJE, estado.getMensaje());
        }
    }

    @Override
    public Object onRetainCustomNonConfigurationInstance() {
        return flow;
    }

    @Override
    protected void onDestroy() {
        if (flow != null) {
            flow.quitarOyente(this);
        }
        super.onDestroy();
    }

    private void enlazarVistas() {
        nombre = findViewById(R.id.checkout_nombre);
        apellido = findViewById(R.id.checkout_apellido);
        email = findViewById(R.id.checkout_email);
        resumen = findViewById(R.id.checkout_resumen);
        formulario = findViewById(R.id.checkout_formulario);
        resultado = findViewById(R.id.checkout_resultado);
        codigoGrupo = findViewById(R.id.checkout_codigo_grupo);
        codigo = findViewById(R.id.checkout_codigo);
        mensajeFormulario = findViewById(R.id.checkout_form_mensaje);
        errorNombre = findViewById(R.id.checkout_error_nombre);
        errorApellido = findViewById(R.id.checkout_error_apellido);
        errorEmail = findViewById(R.id.checkout_error_email);
        tituloResultado = findViewById(R.id.checkout_resultado_titulo);
        mensajeResultado = findViewById(R.id.checkout_resultado_mensaje);
        detalleCorreccion = findViewById(R.id.checkout_correccion_detalle);
        realizarPedido = findViewById(R.id.checkout_realizar_pedido);
        aplicarCorreccion = findViewById(R.id.checkout_aplicar_correccion);
        reenviarEmail = findViewById(R.id.checkout_reenviar_email);
        validarCodigo = findViewById(R.id.checkout_validar_codigo);
        pagar = findViewById(R.id.checkout_pagar);
        finalizar = findViewById(R.id.checkout_finalizar);
        volverCarrito = findViewById(R.id.checkout_volver_carrito);
    }

    private void configurarAcciones() {
        realizarPedido.setOnClickListener(v -> {
            limpiarErrores();
            flow.realizarPedido(nombre.getText().toString(), apellido.getText().toString(),
                    email.getText().toString());
        });
        aplicarCorreccion.setOnClickListener(v -> {
            if (!flow.aplicarCorreccionAlCarrito()) {
                mensajeResultado.setText(R.string.checkout_error_aplicar_correccion);
            }
        });
        reenviarEmail.setOnClickListener(v -> flow.reenviarCodigo());
        validarCodigo.setOnClickListener(v -> flow.validarCodigo(codigo.getText().toString()));
        pagar.setOnClickListener(v -> flow.reintentarPago());
        finalizar.setOnClickListener(v -> {
            if (flow.getEstado().getPantalla() == CheckoutFlow.Pantalla.INGRESO_CODIGO) {
                // Deja el pedido y el carrito intactos; sincroniza catálogo/disponibilidad.
                flow.posponerVerificacion();
                finish();
                return;
            }
            flow.cerrar();
            if (flujoDelProceso == flow) {
                flujoDelProceso = null;
            }
            finish();
        });
        volverCarrito.setOnClickListener(v -> finish());
    }

    @Override
    public void alCambiarEstado(CheckoutFlow.Estado estado) {
        actualizarResumen();
        limpiarErrores();

        CheckoutFlow.Pantalla pantalla = estado.getPantalla();
        boolean mostrarFormulario = pantalla == CheckoutFlow.Pantalla.FORMULARIO
                || pantalla == CheckoutFlow.Pantalla.ENVIANDO_PEDIDO
                || pantalla == CheckoutFlow.Pantalla.ERROR_VALIDACION
                || pantalla == CheckoutFlow.Pantalla.ERROR;
        formulario.setVisibility(mostrarFormulario ? View.VISIBLE : View.GONE);
        resultado.setVisibility(mostrarFormulario ? View.GONE : View.VISIBLE);
        volverCarrito.setVisibility(
                pantalla == CheckoutFlow.Pantalla.INGRESO_CODIGO
                        || pantalla == CheckoutFlow.Pantalla.CODIGO_CONFIRMADO
                        ? View.GONE : View.VISIBLE);
        volverCarrito.setEnabled(!flow.isOperacionEnCurso());

        boolean cargando = pantalla == CheckoutFlow.Pantalla.ENVIANDO_PEDIDO;
        realizarPedido.setEnabled(!cargando && !Carrito.obtenerInstancia().getItems().isEmpty());
        if (cargando) {
            realizarPedido.setText(R.string.checkout_enviando);
        } else {
            realizarPedido.setText(R.string.checkout_realizar_pedido);
        }

        boolean validando = pantalla == CheckoutFlow.Pantalla.VALIDANDO_CODIGO;
        boolean reenviando = pantalla == CheckoutFlow.Pantalla.REENVIANDO_CODIGO;
        boolean enIngresoCodigo = pantalla == CheckoutFlow.Pantalla.INGRESO_CODIGO;
        boolean enCodigo = enIngresoCodigo || validando || reenviando;
        codigoGrupo.setVisibility(enCodigo ? View.VISIBLE : View.GONE);
        codigo.setEnabled(!validando && !reenviando);
        validarCodigo.setEnabled(!validando && !reenviando);
        validarCodigo.setText(validando
                ? R.string.checkout_validando : R.string.checkout_validar_codigo);
        reenviarEmail.setVisibility(enIngresoCodigo || reenviando
                ? View.VISIBLE : View.GONE);
        reenviarEmail.setEnabled(!reenviando);
        reenviarEmail.setText(reenviando
                ? R.string.checkout_reenviando : R.string.checkout_reenviar_codigo);
        boolean pagarDisponible = pantalla == CheckoutFlow.Pantalla.PAGO_LISTO
                || pantalla == CheckoutFlow.Pantalla.PAGO_RECHAZADO
                || pantalla == CheckoutFlow.Pantalla.PAGO_ERROR;
        pagar.setVisibility(pagarDisponible ? View.VISIBLE : View.GONE);
        pagar.setEnabled(pagarDisponible && !flow.isOperacionEnCurso());
        pagar.setText(R.string.checkout_pagar);
        boolean mostrarFinalizar = pantalla == CheckoutFlow.Pantalla.INGRESO_CODIGO
                || pantalla == CheckoutFlow.Pantalla.PAGO_CONFIRMADO
                || pantalla == CheckoutFlow.Pantalla.PAGO_REVISION
                || pantalla == CheckoutFlow.Pantalla.PAGO_RESULTADO;
        finalizar.setVisibility(mostrarFinalizar ? View.VISIBLE : View.GONE);
        finalizar.setText(pantalla == CheckoutFlow.Pantalla.INGRESO_CODIGO
                ? R.string.checkout_dejar_para_despues : R.string.checkout_entendido);

        mensajeFormulario.setVisibility(View.GONE);
        if (pantalla == CheckoutFlow.Pantalla.ERROR_VALIDACION
                || pantalla == CheckoutFlow.Pantalla.ERROR
                || (pantalla == CheckoutFlow.Pantalla.FORMULARIO
                && estado.getMensaje() != null)) {
            String campo = estado.getCampoConError();
            if ("nombre".equals(campo)) {
                mostrarError(errorNombre, estado.getMensaje());
            } else if ("apellido".equals(campo)) {
                mostrarError(errorApellido, estado.getMensaje());
            } else if ("email".equals(campo)) {
                mostrarError(errorEmail, estado.getMensaje());
            } else if (estado.getMensaje() != null) {
                mensajeFormulario.setText(estado.getMensaje());
                mensajeFormulario.setVisibility(View.VISIBLE);
                boolean esError = pantalla == CheckoutFlow.Pantalla.ERROR
                        || pantalla == CheckoutFlow.Pantalla.ERROR_VALIDACION;
                mensajeFormulario.setBackgroundResource(esError
                        ? R.drawable.bg_estado_error : R.drawable.bg_estado);
                mensajeFormulario.setTextColor(ContextCompat.getColor(this, esError
                        ? R.color.error_texto : R.color.texto_claro));
            }
        }

        aplicarCorreccion.setVisibility(pantalla == CheckoutFlow.Pantalla.CORRECCION_COMERCIAL
                ? View.VISIBLE : View.GONE);
        detalleCorreccion.setVisibility(pantalla == CheckoutFlow.Pantalla.CORRECCION_COMERCIAL
                ? View.VISIBLE : View.GONE);

        switch (pantalla) {
            case INGRESO_CODIGO:
                tituloResultado.setText(R.string.checkout_verificar_titulo);
                mensajeResultado.setText(describirIngresoCodigo(estado));
                detalleCorreccion.setText("");
                break;
            case VALIDANDO_CODIGO:
            case REENVIANDO_CODIGO:
                tituloResultado.setText(R.string.checkout_verificar_titulo);
                mensajeResultado.setText(describirConPedido(estado, estado.getMensaje()));
                detalleCorreccion.setText("");
                break;
            case CODIGO_CONFIRMADO:
            case PAGO_PROCESANDO:
            case PAGO_RECUPERANDO:
                tituloResultado.setText(R.string.checkout_pago_procesando_titulo);
                mensajeResultado.setText(describirConPedido(estado, estado.getMensaje()));
                detalleCorreccion.setText("");
                break;
            case PAGO_LISTO:
                tituloResultado.setText(R.string.checkout_pago_listo_titulo);
                mensajeResultado.setText(describirConPedido(estado, estado.getMensaje()));
                detalleCorreccion.setText("");
                break;
            case PAGO_RECHAZADO:
                tituloResultado.setText(R.string.checkout_pago_rechazado_titulo);
                mensajeResultado.setText(describirConPedido(estado, estado.getMensaje()));
                detalleCorreccion.setText("");
                break;
            case PAGO_ERROR:
                tituloResultado.setText(R.string.checkout_pago_error_titulo);
                mensajeResultado.setText(describirConPedido(estado, estado.getMensaje()));
                detalleCorreccion.setText("");
                break;
            case PAGO_CONFIRMADO:
                tituloResultado.setText(R.string.checkout_pago_confirmado_titulo);
                mensajeResultado.setText(describirConPedido(estado, estado.getMensaje()));
                detalleCorreccion.setText("");
                break;
            case PAGO_REVISION:
                tituloResultado.setText(R.string.checkout_pago_revision_titulo);
                mensajeResultado.setText(describirConPedido(estado, estado.getMensaje()));
                detalleCorreccion.setText("");
                break;
            case PAGO_RESULTADO:
                tituloResultado.setText(R.string.checkout_pago_resultado_titulo);
                mensajeResultado.setText(describirConPedido(estado, estado.getMensaje()));
                detalleCorreccion.setText("");
                break;
            case CORRECCION_COMERCIAL:
                tituloResultado.setText(R.string.checkout_correccion_titulo);
                mensajeResultado.setText(estado.getMensaje());
                detalleCorreccion.setText(describirCorreccion(estado.getRespuesta()));
                break;
            case RESULTADO_INCIERTO:
                tituloResultado.setText(R.string.checkout_incierto_titulo);
                mensajeResultado.setText(estado.getMensaje());
                detalleCorreccion.setText("");
                break;
            default:
                tituloResultado.setText(R.string.checkout_error_titulo);
                mensajeResultado.setText(estado.getMensaje());
                detalleCorreccion.setText("");
                break;
        }
    }

    private String describirIngresoCodigo(CheckoutFlow.Estado estado) {
        String texto = estado.getPedidoId() > 0
                ? getString(R.string.checkout_creado_id, estado.getPedidoId()) + "\n"
                : "";
        String mensaje = estado.getMensaje();
        if (mensaje != null && !mensaje.isEmpty()) {
            return texto + mensaje;
        }
        return texto + getString(estado.isEmailEnviado()
                ? R.string.checkout_creado_email : R.string.checkout_creado_sin_email);
    }

    private String describirConPedido(CheckoutFlow.Estado estado, String mensaje) {
        String texto = estado.getPedidoId() > 0
                ? getString(R.string.checkout_creado_id, estado.getPedidoId()) + "\n"
                : "";
        return texto + (mensaje == null ? "" : mensaje);
    }

    private void actualizarResumen() {
        java.util.List<Carrito.Item> items = Carrito.obtenerInstancia().getItems();
        if (items.isEmpty()) {
            resumen.setText(R.string.checkout_error_carrito_vacio);
            realizarPedido.setEnabled(false);
            return;
        }

        StringBuilder texto = new StringBuilder();
        for (Carrito.Item item : items) {
            Producto producto = item.getProducto();
            texto.append(producto.getNombre()).append("\n")
                    .append(getString(R.string.checkout_linea_resumen,
                            item.getCantidad(), producto.getPrecioFormateado(),
                            Producto.formatearCentavos(item.getSubtotalCentavos())))
                    .append("\n\n");
        }
        texto.append(getString(R.string.checkout_total,
                Producto.formatearCentavos(Carrito.obtenerInstancia().getTotalCentavos())));
        resumen.setText(texto.toString().trim());
    }

    private String describirCorreccion(CheckoutResponse respuesta) {
        if (respuesta == null || respuesta.getDatos() == null) {
            return "";
        }
        JSONObject datos = respuesta.getDatos();
        Map<Integer, String> nombres = new HashMap<>();
        for (Carrito.Item item : Carrito.obtenerInstancia().getItems()) {
            nombres.put(item.getProducto().getId(), item.getProducto().getNombre());
        }

        StringBuilder texto = new StringBuilder();
        texto.append(getString(R.string.checkout_correccion_carrito));
        JSONArray lineas = datos.optJSONArray("carrito_corregido");
        if (lineas == null || lineas.length() == 0) {
            texto.append("\n").append(getString(R.string.checkout_correccion_sin_lineas));
        } else {
            for (int i = 0; i < lineas.length(); i++) {
                JSONObject linea = lineas.optJSONObject(i);
                if (linea == null) continue;
                int id = linea.optInt("producto_id", -1);
                String nombreProducto = nombres.get(id);
                if (nombreProducto == null) {
                    nombreProducto = getString(R.string.checkout_producto_id, id);
                }
                texto.append("\n• ").append(nombreProducto).append(" — ")
                        .append(getString(R.string.checkout_cantidad_precio,
                                linea.optInt("cantidad", 0),
                                Producto.formatearPrecio(linea.optDouble("precio_unitario", 0))));
            }
        }

        JSONArray motivos = datos.optJSONArray("motivos");
        if (motivos != null && motivos.length() > 0) {
            texto.append("\n\n").append(getString(R.string.checkout_motivos));
            for (int i = 0; i < motivos.length(); i++) {
                JSONObject motivo = motivos.optJSONObject(i);
                if (motivo == null) continue;
                int id = motivo.optInt("producto_id", -1);
                String nombreProducto = nombres.get(id);
                if (nombreProducto == null) {
                    nombreProducto = getString(R.string.checkout_producto_id, id);
                }
                texto.append("\n• ").append(nombreProducto).append(": ")
                        .append(describirMotivo(motivo));
            }
        }
        return texto.toString();
    }

    private String describirMotivo(JSONObject motivo) {
        String codigo = motivo.optString("motivo", "");
        switch (codigo) {
            case "precio_cambiado":
                return getString(R.string.checkout_motivo_precio,
                        Producto.formatearPrecio(motivo.optDouble("precio_actual", 0)));
            case "disponibilidad_insuficiente":
                return getString(R.string.checkout_motivo_disponibilidad,
                        motivo.optInt("disponibilidad_actual", 0));
            case "producto_no_disponible":
            case "producto_inexistente":
                return getString(R.string.checkout_motivo_no_disponible);
            case "carrito_desactualizado":
                return getString(R.string.checkout_motivo_carrito);
            default:
                return codigo.isEmpty() ? getString(R.string.checkout_motivo_generico) : codigo;
        }
    }

    private void mostrarError(TextView vista, String mensaje) {
        vista.setText(mensaje);
        vista.setVisibility(View.VISIBLE);
    }

    private void limpiarErrores() {
        errorNombre.setText("");
        errorApellido.setText("");
        errorEmail.setText("");
        errorNombre.setVisibility(View.GONE);
        errorApellido.setVisibility(View.GONE);
        errorEmail.setVisibility(View.GONE);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (flow != null && flow.isOperacionEnCurso()) {
            Toast.makeText(this, R.string.checkout_esperar_operacion, Toast.LENGTH_SHORT).show();
            return;
        }
        if (flow != null) {
            CheckoutFlow.Pantalla pantalla = flow.getEstado().getPantalla();
            if (pantalla == CheckoutFlow.Pantalla.INGRESO_CODIGO
                    || pantalla == CheckoutFlow.Pantalla.CODIGO_CONFIRMADO) {
                Toast.makeText(this, R.string.checkout_no_salir_estado, Toast.LENGTH_LONG).show();
                return;
            }
        }
        super.onBackPressed();
    }
}
