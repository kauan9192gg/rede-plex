package com.codigoresgate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Code {
    public String name;
    public String creator;
    /** -1 = ilimitado */
    public int maxUses = -1;
    public int used = 0;
    /** 0 = nunca expira; senao, momento da expiracao em milissegundos */
    public long expiresAt = 0L;
    /** Entradas no formato "console:comando" ou "user:comando" */
    public List<String> commands = new ArrayList<String>();
    /** Nomes (minusculos) dos jogadores que ja usaram */
    public Set<String> usedBy = new HashSet<String>();

    public boolean isExpired() {
        return expiresAt != 0L && System.currentTimeMillis() > expiresAt;
    }

    public boolean isExhausted() {
        return maxUses != -1 && used >= maxUses;
    }
}
