package mx.nico.sharkhome;

import android.service.notification.NotificationListenerService;

/**
 * Vacío a propósito. Android solo deja ver "qué está sonando" (MediaSessionManager) a apps que tienen
 * acceso a notificaciones; este servicio es la credencial. No lee ni guarda notificaciones.
 */
public class MediaListener extends NotificationListenerService {
}
