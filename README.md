# Incancelables Android

**Trabajo Práctico Integrador N.º 2 — UPSO**  
**Tema:** aplicación móvil nativa para el MVP de e-commerce Incancelables.  
**Tecnologías:** Java, Android SDK, layouts XML y API REST sobre HTTPS.  
**Compatibilidad mínima declarada:** Android 6.0 (API 23) o posterior.  
**Fecha de entrega indicada en la consigna:** 24 de noviembre de 2026, 23:59.

**Autor:** Facundo Araujo.  
**Modalidad:** individual.

## Presentación

Incancelables Android es un cliente móvil nativo del MVP académico de comercio electrónico de Incancelables. Permite consultar el catálogo publicado por el backend, seleccionar productos, armar un carrito local e iniciar el checkout como invitado. La aplicación está escrita en Java y usa pantallas Android nativas; no es una WebView del sitio.

La app comparte las reglas y la API REST del e-commerce académico con la tienda web. El backend mantiene la autoridad sobre precios, disponibilidad, estados de pedido y procesamiento del pago simulado.

| Recurso | URL |
| --- | --- |
| API académica — catálogo | <https://incancelables-ecommerce-academico-production.up.railway.app/api/productos> |
| Tienda web académica | <https://xb3nderx.github.io/incancelables-frontend-academico/pages/tienda.html> |
| Repositorio backend | <https://github.com/xb3nderx/incancelables-ecommerce-academico> |
| Repositorio frontend | <https://github.com/xb3nderx/incancelables-frontend-academico> |
| Repositorio Android | <https://github.com/xb3nderx/incancelables-android-academico> |
| Video de demostración | <https://youtu.be/MPW4hA3gDBA> |
| APK de depuración | `app-debug.apk` (entrega en el campus virtual) |

## Alcance del MVP móvil

La implementación incluye:

- Consulta HTTPS del catálogo y presentación de estados de carga, lista vacía, error y reintento.
- Navegación nativa entre catálogo, detalle, carrito y checkout.
- Carrito temporal en memoria, con cantidades, límites de disponibilidad, subtotales y total.
- Creación de pedidos de checkout invitado y tratamiento de correcciones comerciales y errores de red/servidor.
- Ingreso en la app del código de seis dígitos recibido por correo, validación, recuperación del pedido y pago simulado.
- Presentación de imágenes del producto en el detalle cuando el catálogo incluye URLs HTTPS; ante su ausencia o fallo se usa un placeholder.

No se implementaron cuentas de usuario, autenticación de clientes ni una pasarela de pago real. La solicitud de pago de esta implementación indica el proveedor `DUMMY` y la simulación `APROBADO`; el backend puede marcar el pedido pagado y afectar el stock. No debe confundirse este flujo con un cobro real.

## Arquitectura

```mermaid
flowchart LR
    subgraph Android[Cliente Android nativo]
        Cat[CatalogoActivity]
        Det[DetalleProductoActivity]
        Car[CarritoActivity]
        Check[CheckoutActivity]
        Modelo[Producto y Carrito]
        API[Api / CheckoutApi]
        Flujo[CheckoutFlow y contratos JSON]
        Img[CargadorImagenes]
        Cat --> Det
        Cat --> Car
        Det --> Car
        Car --> Check
        Cat --> Modelo
        Det --> Modelo
        Car --> Modelo
        Check --> Flujo --> API
        Cat --> API
        Det --> Img
    end

    API -->|HTTPS, JSON| Backend[API REST PHP en Railway]
    Img -->|HTTPS, recursos estáticos| Public[public/ del backend]
    Backend --> DB[(MySQL / MariaDB)]
    Backend -->|envío de verificación| Resend[Resend, gestionado por backend]
    Web[Frontend académico en GitHub Pages] -->|HTTPS, JSON| Backend
```

Las Activities presentan la interfaz; `Producto` y `Carrito` mantienen los datos de catálogo y selección. `Api` consulta el catálogo de forma asíncrona y conserva su resultado en memoria. `CheckoutFlow` coordina estados y reglas de pantalla, `CheckoutRequest`/`CheckoutResponse` componen e interpretan JSON y `CheckoutApi` realiza el transporte HTTP en un ejecutor de trabajo, entregando el resultado al hilo principal. `CargadorImagenes` descarga recursos HTTPS fuera del hilo de interfaz, con caché de memoria, deduplicación de solicitudes y placeholder.

## Tecnologías y decisiones técnicas

- **Java** para la lógica y **XML** para las interfaces, con Activities e Intents explícitos.
- **AndroidX AppCompat**, Material Components y RecyclerView; no se usan Retrofit, OkHttp, Gson ni SDK de Firebase.
- **`HttpURLConnection`**, `org.json`, `ExecutorService` y `Handler` para las operaciones REST sin bloquear el hilo principal.
- Una única URL base de API en `Api.URL_BASE`; no hay credenciales en el código.
- HTTPS obligatorio en el manifiesto (`usesCleartextTraffic="false"`). `SslConfianza` agrega raíces públicas ISRG X1/X2 al almacén del sistema para compatibilidad con dispositivos API 23 y deja intacta la verificación del nombre de host.
- La interfaz sigue la paleta del frontend académico (fondos oscuros, tarjetas claras y acento naranja) adaptada a pantallas móviles. Los textos de interfaz residen en `strings.xml`.
- El catálogo puede incluir una lista opcional `imagenes` con URL, texto alternativo y orden. El detalle la presenta como galería; las imágenes no se descargan en el hilo principal.

## Pantallas

| Activity | Función implementada |
| --- | --- |
| `CatalogoActivity` | Consulta y muestra productos, disponibilidad y acciones para abrir detalle, agregar una unidad o abrir el carrito. Incluye carga, vacío, error y reintento. |
| `DetalleProductoActivity` | Recibe datos del producto mediante extras de Intent, muestra descripción, precio, disponibilidad y galería opcional; permite agregar una unidad. No realiza una consulta de detalle al backend. |
| `CarritoActivity` | Lista las selecciones, permite aumentar/disminuir cantidades o eliminar líneas, muestra subtotales y total y continúa al checkout. |
| `CheckoutActivity` | Presenta resumen y datos de comprador; administra creación del pedido, ingreso/reenvío/validación del código, revisión de cambios comerciales y estados del pago. No es una pantalla separada de estado de pedido. |

## Flujo de compra

1. La app solicita el catálogo y muestra los productos disponibles.
2. El usuario abre el detalle mediante Intent con datos del producto en extras, o agrega directamente una unidad desde el catálogo.
3. El carrito permite modificar cantidades dentro de la disponibilidad conocida. El carrito no reserva stock y permanece en memoria del proceso.
4. En checkout se completan nombre, apellido y email. La app valida los datos antes del envío y presenta el resumen.
5. Se crea el pedido en el backend. Si el backend devuelve una corrección comercial, la app muestra las diferencias y solo actualiza el carrito tras la acción explícita del usuario.
6. El backend envía un código de seis dígitos al email. El usuario lo ingresa en Android; la app solicita su validación y luego recupera los pedidos autorizados.
7. Cuando el pedido se puede pagar, la app solicita el pago simulado. Presenta el resultado informado por el backend y refresca el catálogo cuando corresponde.

La app bloquea pulsaciones duplicadas mientras mantiene una operación en curso y no reintenta automáticamente solicitudes POST con resultado incierto. No existe sincronización de un carrito persistente entre instalaciones o dispositivos.

## Endpoints REST consumidos

La raíz configurada en `Api.URL_BASE` es:

```text
https://incancelables-ecommerce-academico-production.up.railway.app/api
```

| Método y ruta | Uso en la app |
| --- | --- |
| `GET /productos` | Carga y refresco del catálogo. |
| `POST /pedidos` | Crear pedido con nombre, apellido, email, líneas y canal `android`. |
| `POST /email-verificaciones/codigo` | Solicitar el reenvío del código para un pedido. |
| `POST /email-verificaciones/validar-codigo` | Validar el código de seis dígitos. |
| `POST /email-verificaciones/pedidos` | Recuperar pedidos asociados a la verificación confirmada. |
| `POST /email-verificaciones/pedidos/reemplazo` | Reemplazar el pedido cuando el usuario acepta una corrección y cambia sus líneas. |
| `POST /pedidos/{id}/pago` | Iniciar el pago simulado del pedido. |

El catálogo de detalle no tiene un endpoint propio: los datos se pasan por Intent. Aunque `CheckoutRequest` contiene un constructor para cancelación, la aplicación no lo invoca y no presenta una acción para cancelar pedidos. Tampoco se consume `GET /productos/{id}`.

## Servicios externos y relación con el sistema

- **Railway:** aloja la API académica PHP/Apache; el backend usa MySQL/MariaDB. Android accede a la API y a las imágenes públicas por HTTPS.
- **Resend:** servicio de correo utilizado por el backend para enviar códigos de verificación. Android no integra el SDK ni llama directamente a Resend.
- **GitHub Pages:** publica el frontend web académico; es otro cliente de la API, no una dependencia de ejecución de la app Android.
- **Servicio cloud adicional en Android (TP2 R4):** no implementado. Railway y GitHub Pages corresponden al TP1 y Resend se integra en el backend, no directamente en Android. La incorporación de FCM para notificar estados de pedido o Firebase Authentication para cuentas de clientes constituye una posible ampliación futura.

No se requiere `google-services.json` porque la aplicación no integra Firebase ni Google Services.

## Estructura del proyecto

```text
IncancelablesAndroid/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/incancelables/android/
│       │   │   ├── Activities, adapters y layouts de catálogo/detalle/carrito/checkout
│       │   │   ├── Api.java, CheckoutApi.java y CheckoutFlow.java
│       │   │   ├── CheckoutRequest.java y CheckoutResponse.java
│       │   │   ├── Producto.java, Carrito.java y SslConfianza.java
│       │   │   └── CargadorImagenes.java
│       │   ├── res/layout/       # Vistas XML
│       │   ├── res/values/       # Colores, tema y textos
│       │   └── res/raw/          # Raíces públicas ISRG X1/X2
│       ├── test/                 # Pruebas JVM
│       └── androidTest/          # Pruebas instrumentadas
├── gradle/libs.versions.toml     # Versiones de plugins y dependencias
├── gradle/wrapper/               # Gradle Wrapper
├── build.gradle.kts
└── settings.gradle.kts
```

## Instalación y ejecución

### Requisitos

- Android Studio con Android SDK Platform 35 y Build Tools compatibles.
- JDK compatible con Android Gradle Plugin 8.7.3; el entorno local registrado en el checkpoint utiliza JDK 21.
- Para compatibilidad mínima: dispositivo o emulador Android 6.0/API 23 o posterior.
- Acceso a Internet para consultar el backend e imágenes HTTPS.

### Configuración

1. Abrir `IncancelablesAndroid/` en Android Studio como proyecto Gradle.
2. Verificar `app/src/main/java/com/incancelables/android/Api.java`. `URL_BASE` apunta al backend académico publicado. Para otro entorno se cambia allí la única constante de URL base; no agregar secretos.
3. Sincronizar Gradle y seleccionar un dispositivo Android API 23 o superior.
4. Ejecutar la configuración `app` desde Android Studio. Para instalar el APK generado, usar la variante `debug`.

La aplicación requiere permiso `INTERNET`. No habilita tráfico HTTP sin cifrar. El repositorio del backend ofrece instrucciones separadas para levantar el servicio localmente; cambiar la URL Android a una dirección local solo funciona si el dispositivo puede alcanzar ese servidor.

### Comandos habituales

Desde la raíz del proyecto:

```bat
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
gradlew.bat connectedDebugAndroidTest
```

La APK de depuración se genera en `app/build/outputs/apk/debug/app-debug.apk`. La compilación de depuración y la ejecución de las pruebas unitarias se completaron satisfactoriamente durante el desarrollo.

## Compatibilidad y pruebas

`minSdk` está configurado en **23** (Android 6.0 o posterior), y `compileSdk`/`targetSdk` en 35. Se eligió API 23 porque las pruebas se realizaron en un Samsung Galaxy J2 Prime SM-G532M con Android 6.0.1 (API 23), que no permite instalar aplicaciones con un `minSdk` superior. Esta decisión permitió realizar las pruebas funcionales e instrumentadas en un dispositivo físico.

El código contiene pruebas JVM para modelo y carrito, y pruebas instrumentadas para API/catálogo, checkout, imágenes y respuestas. Los resultados registrados son:

- **5 pruebas JVM aprobadas**, en los resultados de `ProductoTest` y `CarritoTest` del 9/11/2026.
- **18 pruebas instrumentadas aprobadas**, ejecutadas en `SM-G532M - 6.0.1` el 8/11/2026.

Los resultados corresponden a las ejecuciones registradas en las fechas indicadas.

## Limitaciones conocidas

- **Cancelación no expuesta:** existe soporte de construcción de la solicitud en el cliente de contratos, pero no está conectado al flujo ni a la interfaz.
- **Carrito temporal:** se conserva en memoria del proceso; no persiste tras cierre forzado o muerte del proceso.
- **Pago académico:** el cliente usa el proveedor simulado `DUMMY` con resultado `APROBADO`; no cobra dinero, pero el backend puede registrar el pedido como pagado y descontar stock. Las pruebas o demostraciones contra el backend publicado pueden producir efectos en su base de datos.
- **Sin cuentas ni gestión:** no hay autenticación, perfil de usuario, administración de catálogo ni historial de compras.

## Requisitos académicos y alcance de la implementación

| Requisito TP2 | Situación observada |
| --- | --- |
| R1 — Java nativo, pantallas e Intents/extras | Cuatro Activities; catálogo abre detalle con extras. Se mantiene minSdk 23 por compatibilidad con el dispositivo físico de pruebas. No se implementó devolución de resultado entre pantallas ni GET de producto por ID. |
| R2 — API HTTPS, GET/POST y operación adicional | GET catálogo y varios POST de checkout; reemplazo y pago modifican el flujo del pedido. |
| R3 — validación y errores | Validaciones locales, respuestas HTTP y estados de red/corrección implementados; pruebas documentadas arriba. |
| R4 — servicio cloud adicional | No implementado directamente en Android. Resend es utilizado por el backend; Railway y GitHub Pages corresponden al TP1. |
| R5 — XML, textos y espera | Layouts XML, textos en `strings.xml`, lista y estados de carga. |
| R6 — rotación y navegación | Se conserva estado de Activities durante recreación; carrito sigue siendo de memoria y la muerte del proceso tiene las limitaciones indicadas. |
| R7 — URL centralizada y ausencia de secretos | URL base única en `Api.URL_BASE`; sin credenciales cloud en el código. |
| R8 — API y web por HTTPS | URLs académicas documentadas en la sección Presentación. |
| R9 — README, APK y video | README en el repositorio, APK de depuración entregada en el campus y [video de demostración](https://youtu.be/MPW4hA3gDBA). |
| R10 — evidencia de participación | Trabajo realizado de manera individual. |

Esta tabla documenta el alcance de la implementación entregada.

## Repositorios relacionados

- **Backend académico — TP Integrador N.º 1:** [incancelables-ecommerce-academico](https://github.com/xb3nderx/incancelables-ecommerce-academico). API REST PHP y base de datos del MVP.
- **Frontend académico — TP Integrador N.º 1:** [incancelables-frontend-academico](https://github.com/xb3nderx/incancelables-frontend-academico). Tienda web que consume el mismo backend.
- **Android — TP Integrador N.º 2:** [incancelables-android-academico](https://github.com/xb3nderx/incancelables-android-academico). Aplicación móvil nativa.


## Conclusión

La aplicación ofrece un recorrido móvil nativo desde el catálogo hasta el pago simulado y reutiliza el backend académico compartido con la tienda web. El proyecto fue desarrollado individualmente y validado en un dispositivo físico. Como posibles ampliaciones se contemplan la integración de un servicio cloud propio de Android y una consulta individual de productos por ID.
