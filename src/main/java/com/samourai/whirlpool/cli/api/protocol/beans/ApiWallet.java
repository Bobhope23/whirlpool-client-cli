package com.samourai.whirlpool.cli.api.protocol.beans;

import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxo;
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxoPriorityComparator;
import java.util.Collection;
import java.util.stream.Collectors;

public class ApiWallet {
  private Collection<ApiUtxo> utxos;
  private long balance;
  private String zpub;

  public ApiWallet(Collection<WhirlpoolUtxo> whirlpoolUtxos, String zpub) {
    this.utxos =
        whirlpoolUtxos
            .stream()
            .sorted(new WhirlpoolUtxoPriorityComparator())
            .map(whirlpoolUtxo -> new ApiUtxo(whirlpoolUtxo))
            .collect(Collectors.toList());
    this.balance =
        whirlpoolUtxos.stream().mapToLong(whirlpoolUtxo -> whirlpoolUtxo.getUtxo().value).sum();
    this.zpub = zpub;
  }

  public Collection<ApiUtxo> getUtxos() {
    return utxos;
  }

  public long getBalance() {
    return balance;
  }

  public String getZpub() {
    return zpub;
  }
}
