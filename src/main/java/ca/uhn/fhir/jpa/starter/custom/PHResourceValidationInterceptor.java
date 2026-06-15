package ca.uhn.fhir.jpa.starter.custom;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

@Interceptor
@Component
public class PHResourceValidationInterceptor {

    private static final Logger ourLog = LoggerFactory.getLogger(PHResourceValidationInterceptor.class);

    // Profile URLs - current PH Core + PH eReferral CI build canonicals
    private static final String PH_CORE_PATIENT_PROFILE =
            "https://fhir.doh.gov.ph/phcore/StructureDefinition/ph-core-patient";

    private static final String EREFERRAL_SERVICEREQUEST_PROFILE =
            "https://fhir.doh.gov.ph/pheref/StructureDefinition/ereferral-service-request";

    private static final String EREFERRAL_ENCOUNTER_PROFILE =
            "https://fhir.doh.gov.ph/pheref/StructureDefinition/ereferral-encounter";

    // PH Core Patient identifier system URLs
    private static final String PHILHEALTH_SYSTEM =
            "http://philhealth.gov.ph/fhir/Identifier/philhealth-id";

    private static final String PHILSYS_SYSTEM =
            "http://philsys.gov.ph/fhir/Identifier/philsys-id";

    // Local/business MRN system. Not a PH Core fixed Patient identifier slice,
    // but you can allow it if your EMR/FHIR server needs MRN.
    private static final String MRN_SYSTEM =
            "http://hospital.smarthealthit.org/identifier/mrn";

    // eReferral Philippine-specific identifier systems.
    // Use these later if you validate Practitioner / Organization / facility identifiers.
    private static final String EREF_PHILHEALTH_OID =
            "urn:oid:2.16.840.1.113883.2.9.4.3.2";

    private static final String EREF_PRC_OID =
            "urn:oid:2.16.840.1.113883.2.9.4.3.3";

    private static final String EREF_NHFR_OID =
            "urn:oid:2.16.840.1.113883.2.9.4.1.1";

    private static final Set<String> ALLOWED_PATIENT_IDENTIFIER_SYSTEMS = Set.of(
            PHILHEALTH_SYSTEM,
            PHILSYS_SYSTEM,
            MRN_SYSTEM,

            // Optional compatibility with eReferral examples / older payloads.
            // Remove this if you want strict PH Core only.
            EREF_PHILHEALTH_OID
    );

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void resourceCreated(IBaseResource theResource, ServletRequestDetails theRequestDetails) {
        validateResource(theResource, RestOperationTypeEnum.CREATE);
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void resourceUpdated(IBaseResource theResource, ServletRequestDetails theRequestDetails) {
        validateResource(theResource, RestOperationTypeEnum.UPDATE);
    }

    private void validateResource(IBaseResource theResource, RestOperationTypeEnum operationType) {
        if (!(theResource instanceof Resource)) {
            return;
        }

        String resourceType = theResource.fhirType();
        ourLog.info("Validating {} resource: {}", operationType, resourceType);

        switch (resourceType) {
            case "Patient":
                validatePatient((Patient) theResource);
                break;

            case "ServiceRequest":
                validateServiceRequest((ServiceRequest) theResource);
                break;

            case "Encounter":
                validateEncounter((Encounter) theResource);
                break;

            case "Organization":
            case "Practitioner":
            case "PractitionerRole":
                ourLog.info("{} resource: allowing official validator to handle profile validation", resourceType);
                break;

            default:
                ourLog.info("Resource type {}: letting HAPI normal validation handle it", resourceType);
                break;
        }
    }

    private void validatePatient(Patient patient) {
        boolean hasPHCoreProfile = patient.getMeta().getProfile().stream()
                .anyMatch(profile -> PH_CORE_PATIENT_PROFILE.equals(profile.getValue()));

        if (!hasPHCoreProfile) {
            throw new UnprocessableEntityException(
                    "Patient resource must declare the PH Core Patient profile: " + PH_CORE_PATIENT_PROFILE
            );
        }

        if (patient.getIdentifier().isEmpty()) {
            throw new UnprocessableEntityException("Patient resource must have at least one identifier");
        }

        boolean hasUsableIdentifier = false;

        for (Identifier identifier : patient.getIdentifier()) {
            String system = identifier.getSystem();
            String value = identifier.getValue();

            if (system == null || system.isBlank()) {
                throw new UnprocessableEntityException("Patient identifier.system is required");
            }

            if (value == null || value.isBlank()) {
                throw new UnprocessableEntityException("Patient identifier.value is required");
            }

            if (!ALLOWED_PATIENT_IDENTIFIER_SYSTEMS.contains(system)) {
                throw new UnprocessableEntityException(
                        "Patient identifier system must be one of: " +
                                "PhilHealth (" + PHILHEALTH_SYSTEM + "), " +
                                "PhilSys (" + PHILSYS_SYSTEM + "), " +
                                "MRN (" + MRN_SYSTEM + "), " +
                                "or eReferral PhilHealth OID (" + EREF_PHILHEALTH_OID + "). " +
                                "Found: " + system
                );
            }

            hasUsableIdentifier = true;
        }

        if (!hasUsableIdentifier) {
            throw new UnprocessableEntityException("Patient resource must have at least one usable identifier");
        }

        if (patient.getName().isEmpty()) {
            throw new UnprocessableEntityException("Patient resource must have at least one name");
        }

        if (patient.getGender() == null || patient.getGender() == Enumerations.AdministrativeGender.UNKNOWN) {
            throw new UnprocessableEntityException("Patient resource must have a known gender");
        }

        if (patient.getBirthDate() == null || patient.getBirthDateElement().isEmpty()) {
            throw new UnprocessableEntityException("Patient resource must have a birthDate");
        }

        ourLog.info("Patient resource validation passed");
    }

    private void validateServiceRequest(ServiceRequest serviceRequest) {
        boolean hasEReferralProfile = serviceRequest.getMeta().getProfile().stream()
                .anyMatch(profile -> EREFERRAL_SERVICEREQUEST_PROFILE.equals(profile.getValue()));

        if (!hasEReferralProfile) {
            throw new UnprocessableEntityException(
                    "ServiceRequest resource must declare the eReferral ServiceRequest profile: " +
                            EREFERRAL_SERVICEREQUEST_PROFILE
            );
        }

        if (serviceRequest.getStatus() == null) {
            throw new UnprocessableEntityException("ServiceRequest resource must have a status");
        }

        if (serviceRequest.getIntent() == null) {
            throw new UnprocessableEntityException("ServiceRequest resource must have an intent");
        }

        if (serviceRequest.getIntent() != ServiceRequest.ServiceRequestIntent.ORDER) {
            throw new UnprocessableEntityException("ServiceRequest intent must be 'order'");
        }

        if (serviceRequest.getSubject() == null || serviceRequest.getSubject().isEmpty()) {
            throw new UnprocessableEntityException("ServiceRequest resource must have a subject");
        }

        String subjectReference = serviceRequest.getSubject().getReference();
        if (!isPatientReference(subjectReference)) {
            throw new UnprocessableEntityException(
                    "ServiceRequest subject must be a Patient reference. Found: " + subjectReference
            );
        }

        if (serviceRequest.getRequester() == null || serviceRequest.getRequester().isEmpty()) {
            throw new UnprocessableEntityException("ServiceRequest resource must have a requester");
        }

        Reference requester = serviceRequest.getRequester();
        String requesterReference = requester.getReference();
        if (requesterReference == null || requesterReference.isBlank()) {
            throw new UnprocessableEntityException("ServiceRequest requester.reference is required");
        }

        if (serviceRequest.getPerformer().isEmpty() && serviceRequest.getSupportingInfo().isEmpty()) {
            throw new UnprocessableEntityException(
                    "ServiceRequest resource must have either performer or supportingInfo"
            );
        }

        ourLog.info("ServiceRequest resource validation passed");
    }

    private void validateEncounter(Encounter encounter) {
        boolean hasEReferralProfile = encounter.getMeta().getProfile().stream()
                .anyMatch(profile -> EREFERRAL_ENCOUNTER_PROFILE.equals(profile.getValue()));

        if (!hasEReferralProfile) {
            ourLog.info("Encounter does not declare eReferral profile - skipping eReferral validation");
            return;
        }

        if (encounter.getSubject() == null || encounter.getSubject().isEmpty()) {
            throw new UnprocessableEntityException("Encounter resource must have a subject");
        }

        String subjectReference = encounter.getSubject().getReference();
        if (!isPatientReference(subjectReference)) {
            throw new UnprocessableEntityException(
                    "Encounter subject must be a Patient reference. Found: " + subjectReference
            );
        }

        if (encounter.getStatus() == null) {
            throw new UnprocessableEntityException("Encounter resource must have a status");
        }

        if (encounter.getClass_() == null || encounter.getClass_().isEmpty()) {
            throw new UnprocessableEntityException("Encounter resource must have a class");
        }

        ourLog.info("Encounter resource validation passed");
    }

    private boolean isPatientReference(String reference) {
        return reference != null &&
                !reference.isBlank() &&
                (reference.startsWith("Patient/") || reference.contains("/Patient/"));
    }
}