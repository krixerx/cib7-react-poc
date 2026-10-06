/**
 * Client for the curated Estonian vehicle registry, the `vehicles` entity the
 * backend's registry module serves from the service pack's descriptor
 * (docs/business/services/vehicle-registration/data/vehicles.md) under
 * /api/public/registry/vehicles.
 *
 * The OwnerVehicleForm uses this to populate its vehicle dropdown. The
 * engine's Task_GetPrice service task hits the same backend path server-
 * side via the http-connector, so the SPA and the engine see the same
 * catalog at any moment.
 */

import { formatNumber } from '../i18n/format';

const VEHICLES_URL = '/api/public/registry/vehicles';

/** Dropdown row — only what the form renders. */
export interface Vehicle {
  id: string;
  name: string;
}

interface VehicleResponse {
  vin: string;
  make: string;
  model: string;
  year: number;
  ageYears: number;
  value: number;
  fuelType: string;
}

/** Formats "VW Golf 1.4 TSI 2018 · €8,400" for the dropdown row. */
function formatLabel(v: VehicleResponse): string {
  const price = formatNumber(v.value, {
    style: 'currency',
    currency: 'EUR',
    maximumFractionDigits: 0,
  });
  return `${v.make} ${v.model} ${v.year} · ${price}`;
}

export async function listVehicles(): Promise<Vehicle[]> {
  const res = await fetch(VEHICLES_URL);
  if (!res.ok) {
    throw new Error(`Vehicle registry ${res.status} ${res.statusText}`);
  }
  const vehicles = (await res.json()) as VehicleResponse[];
  return vehicles.map((v) => ({ id: v.vin, name: formatLabel(v) }));
}
