import type { TFunction } from 'i18next';

/**
 * The engine returns English display names (process, task, and activity
 * names baked into the BPMN models). The frontend cannot ask the engine for
 * Arabic, so known names are mapped to keys under `common:backend.*` and
 * unknown ones (e.g. free-text from civil servants) fall through untouched.
 *
 * Keep in sync with the `name=` attributes in
 * cib7/src/main/resources/processes/*.bpmn.
 */
const BACKEND_NAME_KEYS: Record<string, string> = {
  // Process definition names
  'Estonian OÜ Registration': 'estonianOuRegistration',
  'Vehicle Registration': 'vehicleRegistration',
  // User tasks
  'Submit OÜ founding details': 'submitOuFoundingDetails',
  'Submit owner & vehicle details': 'submitOwnerVehicleDetails',
  'Transport Authority review': 'transportAuthorityReview',
  // Business registration activities
  'Attach Articles of Association': 'attachArticlesOfAssociation',
  'Wait for submit-to-register': 'waitForSubmitToRegister',
  'Generate state fee invoice': 'generateStateFeeInvoice',
  'Store fee invoice': 'storeFeeInvoice',
  'Send approval email': 'sendApprovalEmail',
  'Wait for state fee payment': 'waitForStateFeePayment',
  'Generate B-card extract': 'generateBcardExtract',
  'Store B-card extract': 'storeBcardExtract',
  'Send sent-back email': 'sendSentBackEmail',
  'OÜ entered in Business Register': 'ouEnteredInBusinessRegister',
  // Vehicle registration activities
  'Attach owner ID document': 'attachOwnerIdDocument',
  'Send owner tracking email': 'sendOwnerTrackingEmail',
  'Send co-owner signing email': 'sendCoOwnerSigningEmail',
  'Wait for co-owner signature': 'waitForCoOwnerSignature',
  'Wait for owner to submit': 'waitForOwnerToSubmit',
  'Look up vehicle in registry': 'lookUpVehicleInRegistry',
  'Send reviewer reminder email': 'sendReviewerReminderEmail',
  'Reminder sent': 'reminderSent',
  'Generate vehicle registration certificate': 'generateVehicleRegistrationCertificate',
  'Store registration certificate': 'storeRegistrationCertificate',
  'Send state fee invoice email': 'sendStateFeeInvoiceEmail',
  'Send "sent back" email': 'sendSentBackEmail',
  'Vehicle registered': 'vehicleRegistered',
};

/**
 * Translates an engine-provided display name, falling back to the original
 * string when it isn't a known BPMN name. Pass the `t` of any component —
 * keys live in the default `common` namespace.
 */
export function translateBackendName(t: TFunction, name: string | null | undefined): string {
  if (!name) return '';
  const key = BACKEND_NAME_KEYS[name];
  return key ? t(`common:backend.${key}`, { defaultValue: name }) : name;
}
