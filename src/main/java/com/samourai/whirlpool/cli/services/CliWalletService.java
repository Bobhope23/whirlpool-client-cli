package com.samourai.whirlpool.cli.services;

import com.google.common.primitives.Bytes;
import com.samourai.http.client.JavaHttpClient;
import com.samourai.wallet.api.pairing.PairingNetwork;
import com.samourai.wallet.api.pairing.PairingPayload;
import com.samourai.wallet.api.pairing.PairingVersion;
import com.samourai.wallet.crypto.AESUtil;
import com.samourai.wallet.hd.HD_Wallet;
import com.samourai.wallet.hd.java.HD_WalletFactoryJava;
import com.samourai.wallet.util.CharSequenceX;
import com.samourai.wallet.util.FormatsUtilGeneric;
import com.samourai.whirlpool.cli.beans.CliState;
import com.samourai.whirlpool.cli.beans.CliStatus;
import com.samourai.whirlpool.cli.beans.WhirlpoolPairingPayload;
import com.samourai.whirlpool.cli.config.CliConfig;
import com.samourai.whirlpool.cli.exception.NoSessionWalletException;
import com.samourai.whirlpool.cli.wallet.CliWallet;
import com.samourai.whirlpool.client.exception.NotifiableException;
import com.samourai.whirlpool.client.utils.ClientUtils;
import com.samourai.whirlpool.client.wallet.WhirlpoolWallet;
import com.samourai.whirlpool.client.wallet.WhirlpoolWalletConfig;
import com.samourai.whirlpool.client.wallet.WhirlpoolWalletService;
import com.samourai.whirlpool.client.wallet.persist.FileWhirlpoolWalletPersistHandler;
import com.samourai.whirlpool.client.wallet.persist.WhirlpoolWalletPersistHandler;
import com.samourai.whirlpool.client.whirlpool.beans.Pools;
import java.io.File;
import java.lang.invoke.MethodHandles;
import java.util.Map;
import javax.crypto.AEADBadTagException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.MnemonicException;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CliWalletService extends WhirlpoolWalletService {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final String INDEX_CLI_VERSION = "cliVersion";
  private static final FormatsUtilGeneric formatUtils = FormatsUtilGeneric.getInstance();

  private CliConfig cliConfig;
  private CliConfigService cliConfigService;
  private HD_WalletFactoryJava hdWalletFactory;
  private WalletAggregateService walletAggregateService;
  private JavaHttpClient httpClient;
  private JavaStompClientService stompClientService;
  private CliTorClientService cliTorClientService;

  // available when wallet is opened
  private CliWallet sessionWallet = null;

  public CliWalletService(
      CliConfig cliConfig,
      CliConfigService cliConfigService,
      HD_WalletFactoryJava hdWalletFactory,
      WalletAggregateService walletAggregateService,
      JavaHttpClient httpClient,
      JavaStompClientService stompClientService,
      CliTorClientService cliTorClientService) {
    super();
    this.cliConfig = cliConfig;
    this.cliConfigService = cliConfigService;
    this.hdWalletFactory = hdWalletFactory;
    this.walletAggregateService = walletAggregateService;
    this.httpClient = httpClient;
    this.stompClientService = stompClientService;
    this.cliTorClientService = cliTorClientService;
  }

  public CliWallet openWallet(String seedPassphrase) throws Exception {
    // require CliStatus.READY
    if (!CliStatus.READY.equals(cliConfigService.getCliStatus())) {
      throw new NotifiableException(
          "Cannot start wallet: cliStatus=" + cliConfigService.getCliStatus());
    }

    NetworkParameters params = cliConfig.getServer().getParams();

    String seedWords;
    try {
      seedWords = decryptSeedWords(cliConfig.getSeed(), seedPassphrase);
    } catch (Exception e) {
      log.error("decryptSeedWords failed, invalid passphrase?");
      if (log.isDebugEnabled()
          && !(e instanceof AEADBadTagException)
          && !(e instanceof InvalidCipherTextException)) {
        log.debug("", e);
      }
      throw new NotifiableException("Decryption failed, invalid passphrase?");
    }

    String walletIdentifier;
    HD_Wallet bip84w;
    try {
      // init wallet from seed
      byte[] seed = hdWalletFactory.computeSeedFromWords(seedWords);
      String walletPassphrase = cliConfig.isSeedAppendPassphrase() ? seedPassphrase : "";
      bip84w = hdWalletFactory.getBIP84(seed, walletPassphrase, params);

      // identifier
      walletIdentifier = computeWalletIdentifier(seed, seedPassphrase, params);
    } catch (MnemonicException e) {
      throw new NotifiableException("Mnemonic failed, invalid passphrase?");
    }

    // debug cliConfig
    if (log.isDebugEnabled()) {
      log.debug("openWallet with cliConfig:");
      for (Map.Entry<String, String> entry : cliConfig.getConfigInfo().entrySet()) {
        log.debug("[cliConfig/" + entry.getKey() + "] " + entry.getValue());
      }
    }

    // open wallet
    WhirlpoolWalletPersistHandler persistHandler = computePersistHandler(walletIdentifier);
    WhirlpoolWalletConfig whirlpoolWalletConfig =
        cliConfig.computeWhirlpoolWalletConfig(httpClient, stompClientService, persistHandler);
    WhirlpoolWallet whirlpoolWallet = openWallet(whirlpoolWalletConfig, bip84w);
    this.sessionWallet =
        new CliWallet(
            whirlpoolWallet,
            cliConfig,
            cliConfigService,
            walletAggregateService,
            cliTorClientService,
            this);

    return sessionWallet;
  }

  private WhirlpoolWalletPersistHandler computePersistHandler(String walletIdentifier)
      throws NotifiableException {
    File indexFile = computeIndexFile(walletIdentifier);
    File utxosFile = computeUtxosFile(walletIdentifier);
    WhirlpoolWalletPersistHandler persistHandler =
        new FileWhirlpoolWalletPersistHandler(indexFile, utxosFile);
    return persistHandler;
  }

  protected String decryptSeedWords(String seedWordsEncrypted, String seedPassphrase)
      throws Exception {
    return AESUtil.decrypt(seedWordsEncrypted, new CharSequenceX(seedPassphrase));
  }

  public void closeWallet() {
    if (this.sessionWallet != null) {
      this.sessionWallet.stop();
      this.sessionWallet = null;
    }
  }

  public CliWallet getSessionWallet() throws NoSessionWalletException {
    if (sessionWallet == null) {
      throw new NoSessionWalletException();
    }
    return sessionWallet;
  }

  public boolean hasSessionWallet() {
    return sessionWallet != null;
  }

  private String computeWalletIdentifier(
      byte[] seed, String seedPassphrase, NetworkParameters params) {
    return ClientUtils.sha256Hash(
        Bytes.concat(seed, seedPassphrase.getBytes(), params.getId().getBytes()));
  }

  private File computeIndexFile(String walletIdentifier) throws NotifiableException {
    String path = "whirlpool-cli-state-" + walletIdentifier + ".json";
    if (log.isDebugEnabled()) {
      log.debug("indexFile: " + path);
    }
    return computeFile(path);
  }

  private File computeUtxosFile(String walletIdentifier) throws NotifiableException {
    String path = "whirlpool-cli-utxos-" + walletIdentifier + ".json";
    if (log.isDebugEnabled()) {
      log.debug("utxosFile: " + path);
    }
    return computeFile(path);
  }

  private File computeFile(String path) throws NotifiableException {
    File f = new File(path);
    if (!f.exists()) {
      if (log.isDebugEnabled()) {
        log.debug("Creating file " + path);
      }
      try {
        f.createNewFile();
      } catch (Exception e) {
        throw new NotifiableException("Unable to write file " + path);
      }
    }
    return f;
  }

  public CliState getCliState() {
    CliStatus cliStatus = cliConfigService.getCliStatus();
    String cliMessage = cliConfigService.getCliMessage();
    boolean loggedIn = hasSessionWallet();

    Integer torProgress = cliTorClientService.getProgress().orElse(null);
    return new CliState(cliStatus, cliMessage, loggedIn, torProgress);
  }

  public String computePairingPayload() {
    PairingNetwork pairingNetwork =
        formatUtils.isTestNet(cliConfig.getServer().getParams())
            ? PairingNetwork.TESTNET
            : PairingNetwork.MAINNET;
    PairingPayload pairingPayload =
        new WhirlpoolPairingPayload(
            PairingVersion.V2_0_0,
            pairingNetwork,
            cliConfig.getSeed(),
            cliConfig.isSeedAppendPassphrase());
    String json = ClientUtils.toJsonString(pairingPayload);
    return json;
  }

  public Pools listPools(CliConfig cliConfig) throws Exception {
    WhirlpoolWalletConfig config =
        cliConfig.computeWhirlpoolWalletConfig(httpClient, stompClientService, null);
    return config.newClient().fetchPools();
  }
}
