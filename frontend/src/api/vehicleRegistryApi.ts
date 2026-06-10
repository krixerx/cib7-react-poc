/**
 * Client for the curated Estonian vehicle registry served by the
 * backend's VehicleRegistryController under
 * /api/public/vehicle-registry.
 *
 * The OwnerVehicleForm uses this to populate its vehicle dropdown. The
 * engine's Task_GetPrice service task hits the same backend path server-
 * side via the http-connector, so the SPA and the engine see the same
 * catalog at any moment.
 */

const VEHICLES_URL = '/api/public/vehicle-registry/vehicles';

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
  const price = v.value.toLocaleString('et-EE', {
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
