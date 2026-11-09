package com.incancelables.android;

/** Frontera injectable entre el flujo de checkout y el transporte HTTP. */
public interface CheckoutSender {
    interface Callback {
        void alCompletar(CheckoutResponse respuesta);
    }

    void enviar(CheckoutRequest solicitud, Callback callback);
}
