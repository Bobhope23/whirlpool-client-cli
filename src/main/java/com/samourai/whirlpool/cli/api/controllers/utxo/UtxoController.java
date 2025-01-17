package com.samourai.whirlpool.cli.api.controllers.utxo;

import com.samourai.whirlpool.cli.api.controllers.AbstractRestController;
import com.samourai.whirlpool.cli.api.protocol.CliApiEndpoint;
import com.samourai.whirlpool.cli.api.protocol.rest.ApiTx0Request;
import com.samourai.whirlpool.cli.api.protocol.rest.ApiTx0Response;
import com.samourai.whirlpool.cli.api.protocol.rest.ApiUtxoConfigureRequest;
import com.samourai.whirlpool.cli.services.CliWalletService;
import com.samourai.whirlpool.client.exception.NotifiableException;
import com.samourai.whirlpool.client.tx0.Tx0;
import com.samourai.whirlpool.client.tx0.Tx0Service;
import com.samourai.whirlpool.client.wallet.WhirlpoolWallet;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxo;
import javax.validation.Valid;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UtxoController extends AbstractRestController {
  @Autowired private CliWalletService cliWalletService;
  @Autowired private Tx0Service tx0Service;

  private WhirlpoolUtxo findUtxo(String utxoHash, int utxoIndex) throws Exception {
    // find utxo
    WhirlpoolUtxo whirlpoolUtxo = cliWalletService.getSessionWallet().findUtxo(utxoHash, utxoIndex);
    if (whirlpoolUtxo == null) {
      throw new NotifiableException("Utxo not found: " + utxoHash + ":" + utxoIndex);
    }
    return whirlpoolUtxo;
  }

  @RequestMapping(value = CliApiEndpoint.REST_UTXO_CONFIGURE, method = RequestMethod.POST)
  public WhirlpoolUtxo configureUtxo(
      @RequestHeader HttpHeaders headers,
      @PathVariable("hash") String utxoHash,
      @PathVariable("index") int utxoIndex,
      @Valid @RequestBody ApiUtxoConfigureRequest payload)
      throws Exception {
    checkHeaders(headers);

    // find utxo
    WhirlpoolUtxo whirlpoolUtxo = findUtxo(utxoHash, utxoIndex);
    WhirlpoolWallet whirlpoolWallet = cliWalletService.getSessionWallet();

    // configure pool
    whirlpoolWallet.setPool(whirlpoolUtxo, payload.poolId);

    // configure mixsTarget
    whirlpoolWallet.setMixsTarget(whirlpoolUtxo, payload.mixsTarget);
    return whirlpoolUtxo;
  }

  @RequestMapping(value = CliApiEndpoint.REST_UTXO_TX0, method = RequestMethod.POST)
  public ApiTx0Response tx0(
      @RequestHeader HttpHeaders headers,
      @PathVariable("hash") String utxoHash,
      @PathVariable("index") int utxoIndex,
      @Valid @RequestBody ApiTx0Request payload)
      throws Exception {
    checkHeaders(headers);

    // find utxo
    WhirlpoolUtxo whirlpoolUtxo = findUtxo(utxoHash, utxoIndex);
    WhirlpoolWallet whirlpoolWallet = cliWalletService.getSessionWallet();

    // override utxo settings
    if (!Strings.isEmpty(payload.poolId)) {
      whirlpoolWallet.setPool(whirlpoolUtxo, payload.poolId);
    }
    if (payload.mixsTarget > 0) {
      whirlpoolWallet.setMixsTarget(whirlpoolUtxo, payload.mixsTarget);
    }

    // tx0
    Tx0 tx0 = whirlpoolWallet.tx0(whirlpoolUtxo, payload.feeTarget);
    return new ApiTx0Response(tx0.getTx().getHashAsString());
  }

  @RequestMapping(value = CliApiEndpoint.REST_UTXO_STARTMIX, method = RequestMethod.POST)
  public void startMix(
      @RequestHeader HttpHeaders headers,
      @PathVariable("hash") String utxoHash,
      @PathVariable("index") int utxoIndex)
      throws Exception {
    checkHeaders(headers);

    // find utxo
    WhirlpoolUtxo whirlpoolUtxo = findUtxo(utxoHash, utxoIndex);
    WhirlpoolWallet whirlpoolWallet = cliWalletService.getSessionWallet();

    // start mix
    whirlpoolWallet.mixQueue(whirlpoolUtxo);
  }

  @RequestMapping(value = CliApiEndpoint.REST_UTXO_STOPMIX, method = RequestMethod.POST)
  public void stopMix(
      @RequestHeader HttpHeaders headers,
      @PathVariable("hash") String utxoHash,
      @PathVariable("index") int utxoIndex)
      throws Exception {
    checkHeaders(headers);

    // find utxo
    WhirlpoolUtxo whirlpoolUtxo = findUtxo(utxoHash, utxoIndex);
    WhirlpoolWallet whirlpoolWallet = cliWalletService.getSessionWallet();

    // stop mix
    whirlpoolWallet.mixStop(whirlpoolUtxo);
  }
}
