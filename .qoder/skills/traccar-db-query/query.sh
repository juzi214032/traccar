#!/usr/bin/env bash
#
# Traccar Database Query Tool
#
# Usage:
#   bash query.sh "SELECT * FROM tc_devices"     # 单条查询
#   bash query.sh -f query.sql                   # 从文件读取
#   bash query.sh                                # 交互模式
#
# Env vars: DB_URL, DB_USER, DB_PASS

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# ---- Resolve Java (Java 21+ required) ----
resolve_java() {
    # Use JAVA_HOME only if it's version 21+
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        local ver
        ver="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')"
        if [ "$ver" -ge 21 ]; then
            echo "$JAVA_HOME"
            return
        fi
    fi
    # Try java_home tool (macOS)
    /usr/libexec/java_home -v 21 2>/dev/null && return || true
    # Fallback: try system java
    echo ""
}
JAVA_BIN="$(resolve_java)"
if [ -n "$JAVA_BIN" ]; then
    JAVAC="$JAVA_BIN/bin/javac"
    JAVA="$JAVA_BIN/bin/java"
else
    JAVAC="javac"
    JAVA="java"
fi

# ---- Load .env if present (skill dir first, then repo root) ----
load_env() {
    local f="$1"
    [ -f "$f" ] || return 0
    while IFS='=' read -r key val; do
        key="$(echo "$key" | tr -d '[:space:]')"
        case "$key" in
            DB_URL|DB_USER|DB_PASS)
                # strip surrounding quotes and trailing inline whitespace
                val="${val%\"}"; val="${val#\"}"
                val="${val%\'}"; val="${val#\'}"
                # only set if not already provided via real environment (even if empty)
                if [ -z "${!key+x}" ]; then export "$key=$val"; fi ;;
        esac
    done < "$f"
}
load_env "$SCRIPT_DIR/.env"
load_env "$REPO_ROOT/.env"

DB_URL="${DB_URL:-jdbc:h2:./target/database}"
DB_USER="${DB_USER:-sa}"
DB_PASS="${DB_PASS:-}"

SQL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        -f|--file) SQL="$(cat "$2")"; shift 2 ;;
        -h|--help)
            echo "Usage: query.sh [SQL] | [-f file.sql] | [interactive]"
            echo "Env: DB_URL (default jdbc:h2:./target/database) DB_USER DB_PASS"
            exit 0 ;;
        *) SQL="$1"; shift ;;
    esac
done

# ---- Resolve JDBC driver jar ----
find_jdbc_jar() {
    local pattern="$1"
    find "$HOME/.gradle/caches" -path "$pattern" -type f 2>/dev/null \
        | grep -v -- '-sources\.jar$' \
        | grep -v -- '-javadoc\.jar$' \
        | sort -V | tail -1
}

download_deps() {
    cd "$REPO_ROOT"
    ./gradlew --no-daemon resolveDependencies 2>/dev/null \
        || ./gradlew --no-daemon dependencies >/dev/null 2>&1 \
        || ./gradlew --no-daemon compileJava >/dev/null 2>&1 \
        || true
}

resolve_driver() {
    local jar
    if [[ "$DB_URL" == *"h2"* ]]; then
        jar="$(find_jdbc_jar "*/com.h2database/h2/*/h2-*.jar")"
    elif [[ "$DB_URL" == *"mysql"* ]]; then
        jar="$(find_jdbc_jar "*/com.mysql/mysql-connector-j/*/mysql-connector-*.jar")"
    elif [[ "$DB_URL" == *"postgresql"* ]] || [[ "$DB_URL" == *"pgsql"* ]]; then
        jar="$(find_jdbc_jar "*/org.postgresql/postgresql/*/postgresql-*.jar")"
    else
        jar="$(find_jdbc_jar "*/com.h2database/h2/*/h2-*.jar")"
    fi

    if [ -z "$jar" ]; then
        echo "Downloading JDBC driver..." >&2
        download_deps
        # Retry
        if [[ "$DB_URL" == *"h2"* ]]; then
            jar="$(find_jdbc_jar "*/com.h2database/h2/*/h2-*.jar")"
        elif [[ "$DB_URL" == *"mysql"* ]]; then
            jar="$(find_jdbc_jar "*/com.mysql/mysql-connector-j/*/mysql-connector-*.jar")"
        elif [[ "$DB_URL" == *"postgresql"* ]] || [[ "$DB_URL" == *"pgsql"* ]]; then
            jar="$(find_jdbc_jar "*/org.postgresql/postgresql/*/postgresql-*.jar")"
        fi
    fi

    echo "$jar"
}

JAR="$(resolve_driver)"
if [ -z "$JAR" ]; then
    echo "ERROR: Cannot find JDBC driver. Run './gradlew build' first." >&2
    exit 1
fi

# ---- Compile & Run ----
TMPDIR="$(mktemp -d)"
trap "rm -rf $TMPDIR" EXIT

cat > "$TMPDIR/Runner.java" << 'JAVAEOF'
import java.io.*;
import java.sql.*;
import java.util.*;

public class Runner {
    static String url, user, pass;
    static Connection conn;

    public static void main(String[] args) throws Exception {
        url = args[0]; user = args[1]; pass = args[2];
        String sql = args.length > 3 ? args[3] : "";

        String driver = "org.h2.Driver";
        if (url.contains("mysql")) driver = "com.mysql.cj.jdbc.Driver";
        else if (url.contains("postgresql") || url.contains("pgsql")) driver = "org.postgresql.Driver";
        Class.forName(driver);

        try (Connection c = DriverManager.getConnection(url, user, pass)) {
            conn = c;
            if (!sql.isEmpty()) {
                run(sql);
            } else {
                interactive();
            }
        }
    }

    static void interactive() throws Exception {
        System.err.println("Traccar DB Query — " + url);
        System.err.println("Type SQL (end with ';') or 'exit'.\n");
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder buf = new StringBuilder();
        while (true) {
            System.err.print("sql> ");
            String line = r.readLine();
            if (line == null) break;
            line = line.trim();
            if (line.equalsIgnoreCase("exit") || line.equalsIgnoreCase("quit")) break;
            if (line.isEmpty() && buf.length() == 0) continue;
            buf.append(" ").append(line);
            if (line.endsWith(";")) {
                String stmt = buf.toString().trim();
                if (stmt.endsWith(";")) stmt = stmt.substring(0, stmt.length() - 1).trim();
                if (!stmt.isEmpty()) run(stmt);
                buf.setLength(0);
            } else if (line.isEmpty()) {
                String stmt = buf.toString().trim();
                if (!stmt.isEmpty()) run(stmt);
                buf.setLength(0);
            }
        }
    }

    static void run(String sql) throws SQLException {
        String u = sql.trim().toUpperCase(Locale.ROOT);
        boolean isQuery = u.startsWith("SELECT") || u.startsWith("SHOW")
                || u.startsWith("DESCRIBE") || u.startsWith("WITH")
                || u.startsWith("EXPLAIN") || u.startsWith("VALUES");

        if (isQuery) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData m = rs.getMetaData();
                int n = m.getColumnCount();
                int[] w = new int[n];
                List<String[]> rows = new ArrayList<>();

                // header
                String[] hdr = new String[n];
                for (int i = 0; i < n; i++) {
                    hdr[i] = m.getColumnName(i + 1);
                    w[i] = hdr[i].length();
                }
                rows.add(hdr);

                // data
                while (rs.next()) {
                    String[] row = new String[n];
                    for (int i = 0; i < n; i++) {
                        String v = rs.getString(i + 1);
                        row[i] = v == null ? "NULL" : v;
                        if (row[i].length() > w[i]) w[i] = row[i].length();
                    }
                    rows.add(row);
                }

                // print
                for (int r = 0; r < rows.size(); r++) {
                    String[] row = rows.get(r);
                    for (int i = 0; i < n; i++) {
                        if (i > 0) System.out.print(" │ ");
                        System.out.printf("%-" + w[i] + "s", row[i]);
                    }
                    System.out.println();
                    if (r == 0) {
                        int total = java.util.Arrays.stream(w).sum() + 3 * (n - 1);
                        System.out.println("-".repeat(total));
                    }
                }
                if (rows.size() > 1) {
                    System.err.println("(" + (rows.size() - 1) + " row(s))");
                }
            }
        } else {
            try (Statement st = conn.createStatement()) {
                int n = st.executeUpdate(sql);
                System.out.println(n + " row(s) affected");
            }
        }
    }
}
JAVAEOF

"$JAVAC" -cp "$JAR" -d "$TMPDIR" "$TMPDIR/Runner.java" 2>/dev/null || {
    echo "ERROR: Compilation failed. Java 21+ required (found: $("$JAVA" -version 2>&1 | head -1))." >&2
    echo "Set JAVA_HOME to a JDK 21+ installation." >&2
    exit 1
}

cd "$REPO_ROOT"
"$JAVA" -cp "$TMPDIR:$JAR" Runner "$DB_URL" "$DB_USER" "$DB_PASS" "$SQL"
