import java.io.FileWriter;
import java.io.PrintWriter;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

/**
 * Reads the server's own tick timer over JMX (server.properties: enable-jmx-monitoring=true).
 * The bean keeps the last 100 tick durations in a ring; every poll writes the entries that changed, so each new
 * tick lands in the CSV once. A tick that blocks the server thread shows up as one long entry.
 * Usage: java TickProbe.java <jmx-port> <out.csv>     (runs until killed)
 */
public class TickProbe {
	public static void main(String[] args) throws Exception {
		int port = Integer.parseInt(args[0]);
		JMXServiceURL url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://127.0.0.1:" + port + "/jmxrmi");
		JMXConnector c = null;
		for (int i = 0; i < 240 && c == null; i++) {
			try {
				c = JMXConnectorFactory.connect(url);
			} catch (Exception e) {
				Thread.sleep(500);
			}
		}
		if (c == null) throw new IllegalStateException("no JMX on port " + port);
		MBeanServerConnection mbs = c.getMBeanServerConnection();
		ObjectName name = new ObjectName("net.minecraft.server:type=Server");
		long[] prev = null;
		try (PrintWriter out = new PrintWriter(new FileWriter(args[1]), true)) {
			out.println("epoch_ms,tick_ms");
			while (true) {
				long[] cur;
				try {
					cur = (long[]) mbs.getAttribute(name, "tickTimes");
				} catch (javax.management.InstanceNotFoundException e) {
					Thread.sleep(250);
					continue;
				}
				long now = System.currentTimeMillis();
				if (prev != null) {
					for (int i = 0; i < cur.length; i++) {
						if (cur[i] != prev[i] && cur[i] > 0) out.printf("%d,%.3f%n", now, cur[i] / 1_000_000.0);
					}
				}
				prev = cur.clone();
				Thread.sleep(100);
			}
		}
	}
}
