import { inject, Injectable } from '@angular/core';
import { createClient, type Client } from '@connectrpc/connect';
import { CONNECT_TRANSPORT } from './connect.provider';

import { AuthService } from '../../../gen/bladewatch/v1/auth_pb';
import { NotificationsService } from '../../../gen/bladewatch/v1/notifications_pb';
import { RecordingsService } from '../../../gen/bladewatch/v1/recordings_pb';
import { SafeLocationsService } from '../../../gen/bladewatch/v1/safe_locations_pb';
import { SettingsService } from '../../../gen/bladewatch/v1/settings_pb';
import { StorageService } from '../../../gen/bladewatch/v1/storage_pb';
import { StreamService } from '../../../gen/bladewatch/v1/stream_pb';
import { SurveillanceService } from '../../../gen/bladewatch/v1/surveillance_pb';
import { SystemService } from '../../../gen/bladewatch/v1/system_pb';
import { TripsService } from '../../../gen/bladewatch/v1/trips_pb';
import { UpdateService } from '../../../gen/bladewatch/v1/update_pb';
import { VehicleService } from '../../../gen/bladewatch/v1/vehicle_pb';

/**
 * Injectable service that exposes typed ConnectRPC clients for all 12
 * BladeWatch services. Inject ConnectClients wherever an RPC call is needed.
 *
 * Example:
 *   constructor(private clients: ConnectClients) {}
 *   const resp = await this.clients.auth.getAuthStatus({});
 */
@Injectable({ providedIn: 'root' })
export class ConnectClients {
  private readonly transport = inject(CONNECT_TRANSPORT);

  readonly auth: Client<typeof AuthService> =
    createClient(AuthService, this.transport);

  readonly notifications: Client<typeof NotificationsService> =
    createClient(NotificationsService, this.transport);

  readonly recordings: Client<typeof RecordingsService> =
    createClient(RecordingsService, this.transport);

  readonly safeLocations: Client<typeof SafeLocationsService> =
    createClient(SafeLocationsService, this.transport);

  readonly settings: Client<typeof SettingsService> =
    createClient(SettingsService, this.transport);

  readonly storage: Client<typeof StorageService> =
    createClient(StorageService, this.transport);

  readonly stream: Client<typeof StreamService> =
    createClient(StreamService, this.transport);

  readonly surveillance: Client<typeof SurveillanceService> =
    createClient(SurveillanceService, this.transport);

  readonly system: Client<typeof SystemService> =
    createClient(SystemService, this.transport);

  readonly trips: Client<typeof TripsService> =
    createClient(TripsService, this.transport);

  readonly updates: Client<typeof UpdateService> =
    createClient(UpdateService, this.transport);

  readonly vehicle: Client<typeof VehicleService> =
    createClient(VehicleService, this.transport);
}
