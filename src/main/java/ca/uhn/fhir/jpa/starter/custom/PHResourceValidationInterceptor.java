package ca.uhn.fhir.jpa.starter.custom;

import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.rest.server.servlet.ServletRequestDetails;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Interceptor
@Component
public class PHResourceValidationInterceptor {

    private static final Logger ourLog = LoggerFactory.getLogger(PHResourceValidationInterceptor.class);

    // Profile URLs
    private static final String PH_CORE_PATIENT_PROFILE = "https://hl7.org.ph/fhir/StructureDefinition/PHCore-Patient";
    private static final String EREFERRAL_SERVICEREQUEST_PROFILE = "https://hl7.org.ph/fhir/StructureDefinition/eReferral-ServiceRequest";
    private static final String EREFERRAL_ENCOUNTER_PROFILE = "https://hl7.org.ph/fhir/StructureDefinition/eReferral-Encounter";

    // Identifier system URLs
    private static final String PHILHEALTH_SYSTEM = "https://nhia.philhealth.gov.ph/phr/identifier/philhealth-id";
    private static final String PHILSYS_SYSTEM = "https://www.philsys.gov.ph/phr/identifier/philsys-id";
    private static final String MRN_SYSTEM = "http://hospital.smarthealthit.org/identifier/mrn";

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
                // Do not force PH Core - let official validator handle if profile is declared
                ourLog.info("{} resource: allowing PH Core validation to be handled by official validator", resourceType);
                break;
            default:
                // For unknown resource types, do not block - let HAPI normal validation handle them
                ourLog.info("Unknown resource type {}: letting HAPI normal validation handle it", resourceType);
                break;
        }
    }

    private void validatePatient(Patient patient) {
        // Require PHCore-Patient profile
        boolean hasPHCoreProfile = patient.getMeta().getProfile().stream()
                .anyMatch(profile -> profile.getValue().equals(PH_CORE_PATIENT_PROFILE));
        
        if (!hasPHCoreProfile) {
            throw new UnprocessableEntityException("Patient resource must declare the PHCore-Patient profile: " + PH_CORE_PATIENT_PROFILE);
        }

        // Require identifier
        if (patient.getIdentifier().isEmpty()) {
            throw new UnprocessableEntityException("Patient resource must have at least one identifier");
        }

        // Allow only PhilHealth, PhilSys, or MRN identifier systems
        for (Identifier identifier : patient.getIdentifier()) {
            String system = identifier.getSystem();
            if (system != null && 
                !system.equals(PHILHEALTH_SYSTEM) && 
                !system.equals(PHILSYS_SYSTEM) && 
                !system.equals(MRN_SYSTEM)) {
                throw new UnprocessableEntityException("Patient identifier system must be one of: PhilHealth (" + 
                    PHILHEALTH_SYSTEM + "), PhilSys (" + PHILSYS_SYSTEM + "), or MRN (" + MRN_SYSTEM + "). Found: " + system);
            }
        }

        // Require name
        if (patient.getName().isEmpty()) {
            throw new UnprocessableEntityException("Patient resource must have at least one name");
        }

        // Require gender
        if (patient.getGender() == null || patient.getGender() == Enumerations.AdministrativeGender.UNKNOWN) {
            throw new UnprocessableEntityException("Patient resource must have a gender");
        }

        // Require birthDate
        if (patient.getBirthDate() == null || patient.getBirthDateElement().isEmpty()) {
            throw new UnprocessableEntityException("Patient resource must have a birthDate");
        }

        ourLog.info("Patient resource validation passed");
    }

    private void validateServiceRequest(ServiceRequest serviceRequest) {
        // Require eReferral ServiceRequest profile
        boolean hasEReferralProfile = serviceRequest.getMeta().getProfile().stream()
                .anyMatch(profile -> profile.getValue().equals(EREFERRAL_SERVICEREQUEST_PROFILE));
        
        if (!hasEReferralProfile) {
            throw new UnprocessableEntityException("ServiceRequest resource must declare the eReferral ServiceRequest profile: " + EREFERRAL_SERVICEREQUEST_PROFILE);
        }

        // Require status
        if (serviceRequest.getStatus() == null) {
            throw new UnprocessableEntityException("ServiceRequest resource must have a status");
        }

        // Require intent
        if (serviceRequest.getIntent() == null) {
            throw new UnprocessableEntityException("ServiceRequest resource must have an intent");
        }

        // Require subject = Patient/...
        if (serviceRequest.getSubject() == null || serviceRequest.getSubject().isEmpty()) {
            throw new UnprocessableEntityException("ServiceRequest resource must have a subject");
        }
        
        String subjectReference = serviceRequest.getSubject().getReference();
        if (subjectReference == null || !subjectReference.startsWith("Patient/")) {
            throw new UnprocessableEntityException("ServiceRequest subject must be a Patient reference (e.g., Patient/...). Found: " + subjectReference);
        }

        // Require requester
        if (serviceRequest.getRequester() == null || serviceRequest.getRequester().isEmpty()) {
            throw new UnprocessableEntityException("ServiceRequest resource must have a requester");
        }

        // Require performer or supporting info depending on workflow
        if (serviceRequest.getPerformer().isEmpty() && (serviceRequest.getSupportingInfo() == null || serviceRequest.getSupportingInfo().isEmpty())) {
            throw new UnprocessableEntityException("ServiceRequest resource must have either performer or supportingInfo");
        }

        ourLog.info("ServiceRequest resource validation passed");
    }

    private void validateEncounter(Encounter encounter) {
        // Only enforce eReferral rules if the Encounter declares an eReferral profile
        boolean hasEReferralProfile = encounter.getMeta().getProfile().stream()
                .anyMatch(profile -> profile.getValue().equals(EREFERRAL_ENCOUNTER_PROFILE));

        if (!hasEReferralProfile) {
            ourLog.info("Encounter does not declare eReferral profile - skipping eReferral validation");
            return;
        }

        // Require subject = Patient/...
        if (encounter.getSubject() == null || encounter.getSubject().isEmpty()) {
            throw new UnprocessableEntityException("Encounter resource must have a subject");
        }
        
        String subjectReference = encounter.getSubject().getReference();
        if (subjectReference == null || !subjectReference.startsWith("Patient/")) {
            throw new UnprocessableEntityException("Encounter subject must be a Patient reference (e.g., Patient/...). Found: " + subjectReference);
        }

        // Require status
        if (encounter.getStatus() == null) {
            throw new UnprocessableEntityException("Encounter resource must have a status");
        }

        // Require class/type if your IG requires it
        if (encounter.getClass_() == null) {
            throw new UnprocessableEntityException("Encounter resource must have a class");
        }

        if (encounter.getType() == null || encounter.getType().isEmpty()) {
            throw new UnprocessableEntityException("Encounter resource must have at least one type");
        }

        ourLog.info("Encounter resource validation passed");
    }
}
