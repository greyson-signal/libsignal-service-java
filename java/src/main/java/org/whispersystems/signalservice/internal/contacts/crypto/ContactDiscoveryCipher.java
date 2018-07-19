package org.whispersystems.signalservice.internal.contacts.crypto;


import org.spongycastle.crypto.InvalidCipherTextException;
import org.spongycastle.crypto.engines.AESFastEngine;
import org.spongycastle.crypto.modes.GCMBlockCipher;
import org.spongycastle.crypto.params.AEADParameters;
import org.spongycastle.crypto.params.KeyParameter;
import org.threeten.bp.Instant;
import org.threeten.bp.LocalDateTime;
import org.threeten.bp.Period;
import org.threeten.bp.ZoneId;
import org.threeten.bp.ZonedDateTime;
import org.threeten.bp.format.DateTimeFormatter;
import org.whispersystems.libsignal.logging.Log;
import org.whispersystems.libsignal.util.ByteUtil;
import org.whispersystems.signalservice.internal.contacts.entities.DiscoveryRequest;
import org.whispersystems.signalservice.internal.contacts.entities.DiscoveryResponse;
import org.whispersystems.signalservice.internal.contacts.entities.RemoteAttestationResponse;
import org.whispersystems.signalservice.internal.util.Hex;
import org.whispersystems.signalservice.internal.util.JsonUtil;
import org.whispersystems.signalservice.internal.util.Util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SignatureException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.List;

public class ContactDiscoveryCipher {

  private static final int MAC_LENGTH_BYTES = 16;
  private static final int MAC_LENGTH_BITS  = MAC_LENGTH_BYTES * 8;

  public DiscoveryRequest createDiscoveryRequest(List<String> addressBook, RemoteAttestation remoteAttestation) {
    try {
      ByteArrayOutputStream requestDataStream = new ByteArrayOutputStream();

      for (String address : addressBook) {
        requestDataStream.write(ByteUtil.longToByteArray(Long.parseLong(address)));
      }

      byte[]         requestData = requestDataStream.toByteArray();
      byte[]         nonce       = Util.getSecretBytes(12);
      GCMBlockCipher cipher      = new GCMBlockCipher(new AESFastEngine());

      cipher.init(true, new AEADParameters(new KeyParameter(remoteAttestation.getKeys().getClientKey()), MAC_LENGTH_BITS, nonce));
      cipher.processAADBytes(remoteAttestation.getRequestId(), 0, remoteAttestation.getRequestId().length);

      byte[] cipherTextCandidate = new byte[cipher.getUpdateOutputSize(requestData.length)];
      cipher.processBytes(requestData, 0, requestData.length, cipherTextCandidate, 0);

      byte[] macCandidate = new byte[cipher.getOutputSize(0)];
      cipher.doFinal(macCandidate, 0);

      byte[] cipherText = cipherTextCandidate;
      byte[] mac        = macCandidate;

      int overflow = macCandidate.length - MAC_LENGTH_BYTES;
      if (overflow > 0) {
        mac = new byte[MAC_LENGTH_BYTES];
        System.arraycopy(macCandidate, overflow, mac, 0, mac.length);

        cipherText = new byte[cipherText.length + overflow];
        System.arraycopy(cipherTextCandidate, 0, cipherText, 0, cipherTextCandidate.length);
        System.arraycopy(macCandidate, 0, cipherText, cipherText.length - overflow, overflow);
      }

      return new DiscoveryRequest(addressBook.size(), remoteAttestation.getRequestId(), nonce, cipherText, mac);
    } catch (IOException | InvalidCipherTextException e) {
      throw new AssertionError(e);
    }
  }

  public byte[] getDiscoveryResponseData(DiscoveryResponse response, RemoteAttestation remoteAttestation)
      throws InvalidCipherTextException
  {
    return decrypt(remoteAttestation.getKeys().getServerKey(), response.getIv(), response.getData(), response.getMac());
  }

  public byte[] getRequestId(RemoteAttestationKeys keys, RemoteAttestationResponse response) throws InvalidCipherTextException {
    return decrypt(keys.getServerKey(), response.getIv(), response.getCiphertext(), response.getTag());
  }

  public void verifyServerQuote(Quote quote, byte[] serverPublicStatic, String mrenclave)
      throws UnauthenticatedQuoteException
  {
    try {
      byte[] theirServerPublicStatic = new byte[serverPublicStatic.length];
      System.arraycopy(quote.getReportData(), 0, theirServerPublicStatic, 0, theirServerPublicStatic.length);

      if (!MessageDigest.isEqual(theirServerPublicStatic, serverPublicStatic)) {
        throw new UnauthenticatedQuoteException("Response quote has unauthenticated report data!");
      }

      if (!MessageDigest.isEqual(Hex.fromStringCondensed(mrenclave), quote.getMrenclave())) {
        throw new UnauthenticatedQuoteException("The response quote has the wrong mrenclave value in it: " + Hex.toStringCondensed(quote.getMrenclave()));
      }

      if (!quote.isDebugQuote()) { // XXX Invert in production
        throw new UnauthenticatedQuoteException("Expecting debug quote!");
      }
    } catch (IOException e) {
      throw new UnauthenticatedQuoteException(e);
    }
  }

  public void verifyIasSignature(KeyStore trustStore, String certificates, String signatureBody, String signature, Quote quote)
      throws SignatureException
  {
    try {
      SigningCertificate signingCertificate = new SigningCertificate(certificates, trustStore);
      signingCertificate.verifySignature(signatureBody, signature);

      SignatureBodyEntity signatureBodyEntity = JsonUtil.fromJson(signatureBody, SignatureBodyEntity.class);

      if (!MessageDigest.isEqual(ByteUtil.trim(signatureBodyEntity.getIsvEnclaveQuoteBody(), 432), ByteUtil.trim(quote.getQuoteBytes(), 432))) {
        throw new SignatureException("Signed quote is not the same as RA quote: " + Hex.toStringCondensed(signatureBodyEntity.getIsvEnclaveQuoteBody()) + " vs " + Hex.toStringCondensed(quote.getQuoteBytes()));
      }

      // TODO: "GROUP_OUT_OF_DATE" should only be allowed during testing
      if (!"OK".equals(signatureBodyEntity.getIsvEnclaveQuoteStatus()) && !"GROUP_OUT_OF_DATE".equals(signatureBodyEntity.getIsvEnclaveQuoteStatus())) {
//      if (!"OK".equals(signatureBodyEntity.getIsvEnclaveQuoteStatus())) {
        throw new SignatureException("Quote status is: " + signatureBodyEntity.getIsvEnclaveQuoteStatus());
      }

      if (Instant.from(ZonedDateTime.of(LocalDateTime.from(DateTimeFormatter.ofPattern("yyy-MM-dd'T'HH:mm:ss.SSSSSS").parse(signatureBodyEntity.getTimestamp())), ZoneId.of("UTC")))
                 .plus(Period.ofDays(1))
                 .isBefore(Instant.now()))
      {
        throw new SignatureException("Signature is expired");
      }

    } catch (CertificateException | CertPathValidatorException | IOException e) {
      throw new SignatureException(e);
    }
  }

  private byte[] decrypt(byte[] key, byte[] iv, byte[] ciphertext, byte[] tag) throws InvalidCipherTextException {
    GCMBlockCipher cipher = new GCMBlockCipher(new AESFastEngine());
    cipher.init(false, new AEADParameters(new KeyParameter(key), 128, iv));

    byte[] combined = ByteUtil.combine(ciphertext, tag);
    byte[] ciphertextOne = new byte[cipher.getUpdateOutputSize(combined.length)];
    cipher.processBytes(combined, 0, combined.length, ciphertextOne, 0);

    byte[] cipherTextTwo = new byte[cipher.getOutputSize(0)];
    cipher.doFinal(cipherTextTwo, 0);

    return ByteUtil.combine(ciphertextOne, cipherTextTwo);
  }
}
