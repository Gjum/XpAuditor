package sh.okx.xpauditor;

import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.entities.Game;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.Role;
import net.dv8tion.jda.core.exceptions.RateLimitedException;
import net.dv8tion.jda.core.utils.IOUtil;
import org.json.JSONObject;
import sh.okx.sql.ConnectionBuilder;
import sh.okx.sql.api.Connection;
import sh.okx.sql.api.PooledConnection;
import sh.okx.sql.api.query.QueryResults;
import sh.okx.xpauditor.xp.Material;
import sh.okx.xpauditor.xp.Nation;
import sh.okx.xpauditor.xp.NationCount;

import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;

public class XpAuditor {
  private PooledConnection pool;

  public static void main(String[] args) throws LoginException, RateLimitedException, IOException {
    JSONObject json = new JSONObject(new String(IOUtil.readFully(XpAuditor.class.getResourceAsStream("/config.json"))));

    new XpAuditor(new JDABuilder(AccountType.BOT)
        .setToken(json.getString("token"))
        .setGame(Game.watching("XP Resources"))
        .buildAsync(), json.getString("database_password"));
  }


  public Nation getNation(Member member) {
    for (Role role : member.getRoles()) {
      for (Nation nation : Nation.values()) {
        if (nation.toString().equalsIgnoreCase(role.getName())) {
          return nation;
        }
      }
    }
    return null;
  }

  private XpAuditor(JDA jda, String password) {
    this.pool = new ConnectionBuilder()
        .setCredentials("root", password)
        .setDatabase("nca")
        .buildPool();

    getConnection().thenAccept(connection ->
        connection.table("xp").create()
            .setIfNotExists(true)
            .column("nation VARCHAR(16)")
            .column("material VARCHAR(36)")
            .column("amount INT(8)")
            .column("PRIMARY KEY (nation, material)")
            .execute());


    jda.addEventListener(new CommandManager(this));
  }

  public CompletableFuture<Connection> getConnection() {
    return CompletableFuture.supplyAsync(() -> pool.getConnection());
  }

  public void deposit(int amount, Material material, Nation nation) {
    getConnection().thenAccept(connection -> connection.executeUpdate(
        "INSERT INTO xp SET nation=?, material=?, amount=? " +
            "ON DUPLICATE KEY UPDATE amount=amount+?",
        nation.name(), material.name(), amount + "", amount + ""));
  }

  public CompletableFuture<Boolean> withdraw(int amount, Material material, Nation nation) {
    return getCount(nation, material).thenApply(sum -> {
      // check if we have enough resources
      if(sum < amount) {
        return false;
      }

      getConnection().thenApply(connection ->
          connection.executeUpdate("INSERT INTO xp SET nation=?, material=?, amount=? "
                  + "ON DUPLICATE KEY UPDATE amount=amount-?",
          nation.name(), material.name(), -amount + "", amount + ""));

      return true;
    });
  }


  private void forceWithdraw(int amount, Material material, Nation nation) {
    getConnection().thenAccept(connection -> connection.executeUpdate(
        "INSERT INTO xp SET nation=?, material=?, amount=? " +
            "ON DUPLICATE KEY UPDATE amount=amount-?",
        nation.name(), material.name(), -amount + "", amount + ""));
  }

  public CompletableFuture<Integer> getContribution(Nation nation) {
    return getConnection().thenApply(connection -> {
      QueryResults results = connection.table("xp")
          .select("amount")
          .where().prepareEquals("nation", nation.name()).then()
          .execute();
      return sum(results.getResultSet());
    });
  }

  public CompletableFuture<Integer> getCount(Material material) {
    return getConnection().thenApply(connection -> {
      QueryResults results = connection.table("xp")
          .select("amount")
          .where().prepareEquals("material", material.name()).then()
          .execute();
      return sum(results.getResultSet());
    });
  }

  public CompletableFuture<Integer> getCount(Nation nation, Material material) {
    return getConnection().thenApply(connection -> sum(getMaterials(connection, nation, material)));
  }

  public Map<Nation, Integer> withdrawBatch(Material material) {
    int needed = material.getAmountNeeded() * 64;
    Map<Nation, Integer> counts = new HashMap<>();

    PriorityQueue<NationCount> queue = new PriorityQueue<>();
    for(Nation nation : Nation.values()) {
      queue.add(new NationCount(this, nation, material));
    }

    while(!queue.isEmpty() && needed > 0) {
      NationCount count = queue.poll();

      int amount = Math.max(0, Math.min(needed, count.getAmount()));
      needed -= count.getAmount();

      forceWithdraw(amount, count.getMaterial(), count.getNation());
      counts.put(count.getNation(), counts.getOrDefault(count.getNation(), 0) + amount);
    }

    return counts;
  }

  public Map<Material, Integer> getAmounts() {
    Map<Material, Integer> amounts = new HashMap<>();
    Arrays.stream(Material.values())
        .forEach(material -> amounts.put(material, getCount(material).join()));
    return amounts;
  }

  private int sum(ResultSet rs) {
    int sum = 0;
    try {
      while(rs.next()) {
        sum += rs.getInt("amount");
      }
    } catch (SQLException e) {
      return 0;
    }
    return sum;
  }

  private ResultSet getMaterials(Connection connection, Nation nation, Material material) {
    return connection.table("xp")
        .select("amount")
        .where().prepareEquals("material", material.name())
        .and().prepareEquals("nation", nation.name())
        .then()
        .execute().getResultSet();
  }
}
