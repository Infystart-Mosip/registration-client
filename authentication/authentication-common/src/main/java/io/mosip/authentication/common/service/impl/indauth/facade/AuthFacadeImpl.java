/*
 * 
 */
package io.mosip.authentication.common.service.impl.indauth.facade;

import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import io.mosip.authentication.common.entity.AutnTxn;
import io.mosip.authentication.common.helper.AuditHelper;
import io.mosip.authentication.common.impl.indauth.service.bio.BioAuthType;
import io.mosip.authentication.common.integration.IdAuthenticationProperties;
import io.mosip.authentication.common.service.impl.indauth.builder.AuthResponseBuilder;
import io.mosip.authentication.core.constant.AuditEvents;
import io.mosip.authentication.core.constant.AuditModules;
import io.mosip.authentication.core.constant.IdAuthenticationErrorConstants;
import io.mosip.authentication.core.constant.RequestType;
import io.mosip.authentication.core.dto.indauth.AuthRequestDTO;
import io.mosip.authentication.core.dto.indauth.AuthResponseDTO;
import io.mosip.authentication.core.dto.indauth.AuthStatusInfo;
import io.mosip.authentication.core.dto.indauth.BioIdentityInfoDTO;
import io.mosip.authentication.core.dto.indauth.IdType;
import io.mosip.authentication.core.dto.indauth.IdentityInfoDTO;
import io.mosip.authentication.core.exception.IdAuthenticationBusinessException;
import io.mosip.authentication.core.logger.IdaLogger;
import io.mosip.authentication.core.spi.id.service.IdAuthService;
import io.mosip.authentication.core.spi.indauth.facade.AuthFacade;
import io.mosip.authentication.core.spi.indauth.match.IdInfoFetcher;
import io.mosip.authentication.core.spi.indauth.service.BioAuthService;
import io.mosip.authentication.core.spi.indauth.service.DemoAuthService;
import io.mosip.authentication.core.spi.indauth.service.OTPAuthService;
import io.mosip.authentication.core.spi.indauth.service.PinAuthService;
import io.mosip.authentication.core.spi.notification.service.NotificationService;
import io.mosip.kernel.core.exception.ParseException;
import io.mosip.kernel.core.idgenerator.spi.TokenIdGenerator;
import io.mosip.kernel.core.logger.spi.Logger;
import io.mosip.kernel.core.util.DateUtils;
import io.mosip.kernel.core.util.UUIDUtils;

/**
 * This class provides the implementation of AuthFacade, provides the
 * authentication for individual by calling the respective Service
 * Classes{@link AuthFacade}.
 *
 * @author Arun Bose
 * 
 * @author Prem Kumar
 */
@Service
public class AuthFacadeImpl implements AuthFacade {

	/** The Constant FAILED. */
	private static final String FAILED = "N";

	/** The Constant IDA. */
	private static final String IDA = "IDA";

	/** The Constant AUTH_FACADE. */
	private static final String AUTH_FACADE = "AuthFacade";

	/** The Constant DEFAULT_SESSION_ID. */
	private static final String DEFAULT_SESSION_ID = "sessionId";

	/** The Constant SUCCESS_STATUS. */
	private static final String SUCCESS_STATUS = "Y";

	/** The logger. */
	private static Logger logger = IdaLogger.getLogger(AuthFacadeImpl.class);

	/** The otp service. */
	@Autowired
	private OTPAuthService otpService;

	/** The id auth service. */
	@Autowired
	private IdAuthService<AutnTxn> idAuthService;


	/** The Environment */
	@Autowired
	private Environment env;

	/** The Id Info Service */
	@Autowired
	private IdAuthService<AutnTxn> idInfoService;

	/** The Demo Auth Service */
	@Autowired
	private DemoAuthService demoAuthService;

	/** The AuditHelper */
	@Autowired
	private AuditHelper auditHelper;

	/** The BioAuthService */
	@Autowired
	private BioAuthService bioAuthService;

	/** The NotificationService */
	@Autowired
	private NotificationService notificationService;

	/** The Pin Auth Service */
	@Autowired
	private PinAuthService pinAuthService;

	/** The TokenId Generator */
	@Autowired
	private TokenIdGenerator<String> tokenIdGenerator;

	/** The Id Info Fetcher */
	@Autowired
	private IdInfoFetcher idInfoFetcher;

	/*
	 * (non-Javadoc)
	 * 
	 * @see io.mosip.authentication.core.spi.indauth.facade.AuthFacade#
	 * authenticateApplicant(io.mosip.authentication.core.dto.indauth.
	 * AuthRequestDTO, boolean, java.lang.String)
	 */
	@Override
	public AuthResponseDTO authenticateApplicant(AuthRequestDTO authRequestDTO, boolean isAuth, String partnerId)
			throws IdAuthenticationBusinessException {

		IdType idType = idInfoFetcher.getUinOrVidType(authRequestDTO);
		Optional<String> idvid = idInfoFetcher.getUinOrVid(authRequestDTO);
		String idvIdType = idType.getType();
		Map<String, Object> idResDTO = idAuthService.processIdType(idvIdType, idvid.orElse(""),
				authRequestDTO.getRequestedAuth().isBio());

		AuthResponseDTO authResponseDTO;
		AuthResponseBuilder authResponseBuilder = AuthResponseBuilder.newInstance(env.getProperty(IdAuthenticationProperties.DATE_TIME_PATTERN.getkey()));
		Map<String, List<IdentityInfoDTO>> idInfo = null;
		String uin = String.valueOf(idResDTO.get("uin"));
		String staticTokenId = null;
		Boolean staticTokenRequired = env.getProperty(IdAuthenticationProperties.STATIC_TOKEN_ENABLE.getkey(), Boolean.class);
		try {
			idInfo = idInfoService.getIdInfo(idResDTO);
			authResponseBuilder.setTxnID(authRequestDTO.getTransactionID());
			// FIXME temporary fix for the api change
//			String staticTokenId = staticTokenRequired ? tokenIdGenerator.generateId(tspId, uin) : "";
			staticTokenId = staticTokenRequired ? tokenIdGenerator.generateId() : "";
			List<AuthStatusInfo> authStatusList = processAuthType(authRequestDTO, idInfo, uin, isAuth, staticTokenId,
					partnerId);
			authStatusList.forEach(authResponseBuilder::addAuthStatusInfo);

		} finally {
			// Set static token
			if (staticTokenRequired) {
				authResponseDTO = authResponseBuilder.build(staticTokenId);
			} else {
				authResponseDTO = authResponseBuilder.build(null);
			}

			logger.info(DEFAULT_SESSION_ID, IDA, AUTH_FACADE,
					"authenticateApplicant status : " + authResponseDTO.getResponse().isAuthStatus());
		}

		if (idInfo != null && uin != null) {
			notificationService.sendAuthNotification(authRequestDTO, uin, authResponseDTO, idInfo, isAuth);
		}

		return authResponseDTO;

	}

	/**
	 * Process the authorisation type and corresponding authorisation service is
	 * called according to authorisation type. reference Id is returned in
	 * AuthRequestDTO.
	 *
	 * @param authRequestDTO the auth request DTO
	 * @param idInfo         list of identityInfoDto request
	 * @param uin            the uin
	 * @param isAuth         the is auth
	 * @return the list
	 * @throws IdAuthenticationBusinessException the id authentication business
	 *                                           exception
	 */
	private List<AuthStatusInfo> processAuthType(AuthRequestDTO authRequestDTO,
			Map<String, List<IdentityInfoDTO>> idInfo, String uin, boolean isAuth, String staticTokenId,
			String partnerId) throws IdAuthenticationBusinessException {

		List<AuthStatusInfo> authStatusList = new ArrayList<>();
		IdType idType = null;

		if (idInfoFetcher.getUinOrVidType(authRequestDTO).getType().equals(IdType.UIN.getType())) {
			idType = IdType.UIN;
		} else {
			idType = IdType.VID;
		}

		processOTPAuth(authRequestDTO, uin, isAuth, authStatusList, idType, staticTokenId, partnerId);

		processDemoAuth(authRequestDTO, idInfo, uin, isAuth, authStatusList, idType, staticTokenId, partnerId);

		processBioAuth(authRequestDTO, idInfo, uin, isAuth, authStatusList, idType, staticTokenId, partnerId);

		processPinAuth(authRequestDTO, uin, isAuth, authStatusList, idType, staticTokenId, partnerId);

		return authStatusList;
	}

	/**
	 * Process the authorisation type and corresponding authorisation service is
	 * called according to authorisation type.
	 * 
	 * @param authRequestDTO
	 * @param uin
	 * @param isAuth
	 * @param authStatusList
	 * @param idType
	 * @throws IdAuthenticationBusinessException
	 */
	private void processPinAuth(AuthRequestDTO authRequestDTO, String uin, boolean isAuth,
			List<AuthStatusInfo> authStatusList, IdType idType, String staticTokenId, String partnerId)
			throws IdAuthenticationBusinessException {
		AuthStatusInfo statusInfo = null;
		if (authRequestDTO.getRequestedAuth().isPin()) {
			AuthStatusInfo pinValidationStatus;
			try {

				pinValidationStatus = pinAuthService.authenticate(authRequestDTO, uin, Collections.emptyMap(),
						partnerId);
				authStatusList.add(pinValidationStatus);
				statusInfo = pinValidationStatus;
			} finally {
				boolean isStatus = statusInfo != null && statusInfo.isStatus();
				logger.info(DEFAULT_SESSION_ID, IDA, AUTH_FACADE, "Pin Authentication  status :" + statusInfo);
				auditHelper.audit(AuditModules.PIN_AUTH, AuditEvents.AUTH_REQUEST_RESPONSE,
						idInfoFetcher.getUinOrVid(authRequestDTO).get(), idType, AuditModules.PIN_AUTH.getDesc());
				AutnTxn auth_txn = createAuthTxn(authRequestDTO, uin, RequestType.STATIC_PIN_AUTH, staticTokenId,
						isStatus);
				idAuthService.saveAutnTxn(auth_txn);
			}
		}
	}

	/**
	 * process the BioAuth
	 * 
	 * @param authRequestDTO
	 * @param idInfo
	 * @param isAuth
	 * @param authStatusList
	 * @param idType
	 * @throws IdAuthenticationBusinessException
	 */
	private void processBioAuth(AuthRequestDTO authRequestDTO, Map<String, List<IdentityInfoDTO>> idInfo, String uin,
			boolean isAuth, List<AuthStatusInfo> authStatusList, IdType idType, String staticTokenId, String partnerId)
			throws IdAuthenticationBusinessException {

		AuthStatusInfo statusInfo = null;
		if (authRequestDTO.getRequestedAuth().isBio()) {
			AuthStatusInfo bioValidationStatus;
			try {
				bioValidationStatus = bioAuthService.authenticate(authRequestDTO, uin, idInfo, partnerId);
				authStatusList.add(bioValidationStatus);
				statusInfo = bioValidationStatus;
			} finally {
				boolean isStatus = statusInfo != null && statusInfo.isStatus();
				logger.info(DEFAULT_SESSION_ID, IDA, AUTH_FACADE, "BioMetric Authentication status :" + statusInfo);
				saveAndAuditBioAuthTxn(authRequestDTO, isAuth, idInfoFetcher.getUinOrVid(authRequestDTO).get(), idType,
						isStatus, staticTokenId);
			}
		}
	}

	/**
	 * Process demo auth.
	 *
	 * @param authRequestDTO the auth request DTO
	 * @param idInfo         the id info
	 * @param uin            the uin
	 * @param isAuth         the is auth
	 * @param authStatusList the auth status list
	 * @param idType         the id type
	 * @throws IdAuthenticationBusinessException the id authentication business
	 *                                           exception
	 */
	private void processDemoAuth(AuthRequestDTO authRequestDTO, Map<String, List<IdentityInfoDTO>> idInfo, String uin,
			boolean isAuth, List<AuthStatusInfo> authStatusList, IdType idType, String staticTokenId, String partnerId)
			throws IdAuthenticationBusinessException {
		AuthStatusInfo statusInfo = null;
		if (authRequestDTO.getRequestedAuth().isDemo()) {
			AuthStatusInfo demoValidationStatus;
			try {
				demoValidationStatus = demoAuthService.authenticate(authRequestDTO, uin, idInfo, partnerId);
				authStatusList.add(demoValidationStatus);
				statusInfo = demoValidationStatus;
			} finally {

				boolean isStatus = statusInfo != null && statusInfo.isStatus();

				logger.info(DEFAULT_SESSION_ID, IDA, AUTH_FACADE, "Demographic Authentication status : " + statusInfo);
				auditHelper.audit(AuditModules.DEMO_AUTH, getAuditEvent(isAuth),
						idInfoFetcher.getUinOrVid(authRequestDTO).get(), idType, AuditModules.DEMO_AUTH.getDesc());

				AutnTxn auth_txn = createAuthTxn(authRequestDTO, uin, RequestType.DEMO_AUTH, staticTokenId, isStatus);
				idAuthService.saveAutnTxn(auth_txn);
			}

		}
	}

	/**
	 * Process OTP auth.
	 *
	 * @param authRequestDTO the auth request DTO
	 * @param uin            the uin
	 * @param isAuth         the is auth
	 * @param authStatusList the auth status list
	 * @param idType         the id type
	 * @throws IdAuthenticationBusinessException the id authentication business
	 *                                           exception
	 */
	private void processOTPAuth(AuthRequestDTO authRequestDTO, String uin, boolean isAuth,
			List<AuthStatusInfo> authStatusList, IdType idType, String staticTokenId, String partnerId)
			throws IdAuthenticationBusinessException {
		AuthStatusInfo statusInfo = null;
		if (authRequestDTO.getRequestedAuth().isOtp()) {
			AuthStatusInfo otpValidationStatus;
			try {
				otpValidationStatus = otpService.authenticate(authRequestDTO, uin, Collections.emptyMap(), partnerId);
				authStatusList.add(otpValidationStatus);
				statusInfo = otpValidationStatus;
			} finally {
				boolean isStatus = statusInfo != null && statusInfo.isStatus();
				logger.info(DEFAULT_SESSION_ID, IDA, AUTH_FACADE, "OTP Authentication status : " + statusInfo);
				auditHelper.audit(AuditModules.OTP_AUTH, getAuditEvent(isAuth),
						idInfoFetcher.getUinOrVid(authRequestDTO).get(), idType, AuditModules.OTP_AUTH.getDesc());

				AutnTxn authTxn = createAuthTxn(authRequestDTO, uin, RequestType.OTP_AUTH, staticTokenId, isStatus);
				idAuthService.saveAutnTxn(authTxn);
			}

		}
	}

	/**
	 * Processed to authentic bio type request.
	 * 
	 * @param authRequestDTO authRequestDTO
	 * @param isAuth         boolean value for verify is auth type request or not.
	 * @param idType         idtype
	 * @param isStatus
	 * @throws IdAuthenticationBusinessException
	 */
	private void saveAndAuditBioAuthTxn(AuthRequestDTO authRequestDTO, boolean isAuth, String uin, IdType idType,
			boolean isStatus, String staticTokenId) throws IdAuthenticationBusinessException {
		if (authRequestDTO.getRequest().getBiometrics().stream().map(BioIdentityInfoDTO::getData)
				.anyMatch(bioInfo -> bioInfo.getBioType().equals(BioAuthType.FGR_MIN.getType())
						|| bioInfo.getBioType().equals(BioAuthType.FGR_IMG.getType()))) {
			auditHelper.audit(AuditModules.FINGERPRINT_AUTH, getAuditEvent(isAuth),
					idInfoFetcher.getUinOrVid(authRequestDTO).get(), idType, AuditModules.FINGERPRINT_AUTH.getDesc());
			AutnTxn authTxn = createAuthTxn(authRequestDTO, uin, RequestType.FINGER_AUTH, staticTokenId, isStatus);
			idAuthService.saveAutnTxn(authTxn);
		}
		if (authRequestDTO.getRequest().getBiometrics().stream().map(BioIdentityInfoDTO::getData)
				.anyMatch(bioInfo -> bioInfo.getBioType().equals(BioAuthType.IRIS_IMG.getType()))) {
			auditHelper.audit(AuditModules.IRIS_AUTH, getAuditEvent(isAuth),
					idInfoFetcher.getUinOrVid(authRequestDTO).get(), idType, AuditModules.IRIS_AUTH.getDesc());
			AutnTxn authTxn = createAuthTxn(authRequestDTO, uin, RequestType.IRIS_AUTH, staticTokenId, isStatus);
			idAuthService.saveAutnTxn(authTxn);
		}
		if (authRequestDTO.getRequest().getBiometrics().stream().map(BioIdentityInfoDTO::getData)
				.anyMatch(bioInfo -> bioInfo.getBioType().equals(BioAuthType.FACE_IMG.getType()))) {
			auditHelper.audit(AuditModules.FACE_AUTH, getAuditEvent(isAuth),
					idInfoFetcher.getUinOrVid(authRequestDTO).get(), idType, AuditModules.FACE_AUTH.getDesc());
			AutnTxn authTxn = createAuthTxn(authRequestDTO, uin, RequestType.FACE_AUTH, staticTokenId, isStatus);
			idAuthService.saveAutnTxn(authTxn);
		}
	}

	/**
	 * sets AuthTxn entity values
	 * 
	 * @param authRequestDTO
	 * @param uin
	 * @param status
	 * @param comment
	 * @param requestType
	 * @return
	 * @throws IdAuthenticationBusinessException
	 */
	private AutnTxn createAuthTxn(AuthRequestDTO authRequestDTO, String uin, RequestType requestType,
			String staticTokenId, boolean isStatus) throws IdAuthenticationBusinessException {
		try {
			String status = isStatus ? SUCCESS_STATUS : FAILED;
			String comment = isStatus ? requestType.getMessage() + " Success" : requestType.getMessage() + " Failed";
			String idvId = idInfoFetcher.getUinOrVid(authRequestDTO).get();
			String reqTime = authRequestDTO.getRequestTime();
			String idvIdType = idInfoFetcher.getUinOrVidType(authRequestDTO).getType();
			String txnID = authRequestDTO.getTransactionID();
			AutnTxn autnTxn = new AutnTxn();
			autnTxn.setRefId(idvId);
			autnTxn.setRefIdType(idvIdType);
			String id = createId(uin);
			autnTxn.setId(id); // FIXME
			autnTxn.setCrBy(IDA);
			autnTxn.setStaticTknId(staticTokenId);
			autnTxn.setCrDTimes(DateUtils.getUTCCurrentDateTime());
//			String strUTCDate = DateUtils
//					.getUTCTimeFromDate(DateUtils.parseToDate(reqTime, env.getProperty(IdAuthenticationProperties.DATE_TIME_PATTERN.getkey())));
//			autnTxn.setRequestDTtimes(DateUtils.parseToLocalDateTime(strUTCDate));
			autnTxn.setResponseDTimes(DateUtils.getUTCCurrentDateTime()); // TODO check this
			autnTxn.setAuthTypeCode(requestType.getRequestType());
			autnTxn.setRequestTrnId(txnID);
			autnTxn.setStatusCode(status);
			autnTxn.setStatusComment(comment);
			// FIXME
			autnTxn.setLangCode(env.getProperty(IdAuthenticationProperties.MOSIP_PRIMARY_LANGUAGE.getkey()));
			return autnTxn;
		} catch (ParseException e) {
			logger.error(DEFAULT_SESSION_ID, this.getClass().getName(), e.getClass().getName(), e.getMessage());
			throw new IdAuthenticationBusinessException(IdAuthenticationErrorConstants.UNABLE_TO_PROCESS, e);
		}
	}

	/**
	 * Creates UUID
	 * 
	 * @param uin
	 * @return
	 */
	private String createId(String uin) {
		String currentDate = DateUtils.formatDate(new Date(), env.getProperty(IdAuthenticationProperties.DATE_TIME_PATTERN.getkey()));
		String uinAndDate = uin + "-" + currentDate;
		return UUIDUtils.getUUID(UUIDUtils.NAMESPACE_OID, uinAndDate).toString();
	}

	/**
	 * Gets the audit event.
	 *
	 * @param isAuth the is auth
	 * @return the audit event
	 */
	private AuditEvents getAuditEvent(boolean isAuth) {
		return isAuth ? AuditEvents.AUTH_REQUEST_RESPONSE : AuditEvents.INTERNAL_REQUEST_RESPONSE;
	}

	/**
	 * This method Accepts the time in String and returns utcTime in String.
	 * 
	 * @param reqTime
	 * @return
	 */
	public String getUTCTime(String reqTime) {
		Date reqDate = DateUtils.parseToDate(reqTime, env.getProperty(IdAuthenticationProperties.DATE_TIME_PATTERN.getkey()));
		SimpleDateFormat dateFormatter = new SimpleDateFormat(env.getProperty(IdAuthenticationProperties.DATE_TIME_PATTERN.getkey()));
		dateFormatter.setTimeZone(TimeZone.getTimeZone(ZoneId.of("UTC")));
		return dateFormatter.format(reqDate);
	}

}
