#!/usr/bin/env bash
set -euo pipefail
umask 077

die() { printf '%s\n' "$*" >&2; exit 1; }
ask() {
    local value
    printf '%s%s: ' "$1" "${2:+ [$2]}" >&2
    IFS= read -r value || die '输入已取消。'
    printf '%s' "${value:-$2}"
}
ipv4() {
    local octet
    local -a octets
    [[ $1 =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || return 1
    IFS=. read -r -a octets <<< "$1"
    for octet in "${octets[@]}"; do
        [[ $octet == 0 || $octet != 0* ]] && (( 10#$octet <= 255 )) || return 1
    done
}
host_name() {
    [[ $1 =~ ^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$ && $1 != *..* && ${#1} -le 253 ]]
}

[[ $(uname -s) == Linux ]] || die '请在运行数据库的 Linux 主机上执行。'
for command in docker openssl java javac curl sha256sum; do
    command -v "$command" >/dev/null || die "缺少 $command。"
done
docker info >/dev/null 2>&1 || die '无法访问本机 Docker。请先启动 Docker 并检查当前用户的权限。'

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
[[ $# -eq 0 ]] || die '用法：bash scripts/bootstrap-mysql.sh'
[[ -f "$script_dir/SetupMySql.java" ]] || die '请将 SetupMySql.java 放在脚本旁边。'
[[ $(javac -version 2>&1) =~ ^javac[[:space:]]25([.]|[[:space:]]|$) ]] || die '请将 JDK 25 放入 PATH。'

bind=$(ask '数据库监听的本机 IPv4（两台机器时填数据库主机的私网地址）' '127.0.0.1')
ipv4 "$bind" && [[ $bind != 0.0.0.0 ]] || die '监听地址需是具体 IPv4，如 127.0.0.1 或 10.0.0.5。'
host=$(ask '两服连接 MySQL 时用的域名或 IPv4（写入证书）' "$bind")
if ipv4 "$host"; then
    san="IP:$host"
else
    host_name "$host" && [[ ! $host =~ ^[0-9.]+$ ]] || die '数据库地址格式无效。'
    san="DNS:$host"
fi
fabric=$(ask 'MySQL 看到的 Fabric 来源 IPv4' '127.0.0.1')
spigot=$(ask 'MySQL 看到的 Spigot 来源 IPv4' "$fabric")
ipv4 "$fabric" && ipv4 "$spigot" || die '两台游戏服的来源地址都需填写具体 IPv4。'
if [[ $bind == 127.0.0.1 && ( $fabric != 127.0.0.1 || $spigot != 127.0.0.1 ) ]]; then
    die '远程游戏服需要数据库监听其可访问的本机网卡地址。'
fi
port=$(ask 'MySQL 端口' '3306')
[[ $port =~ ^[1-9][0-9]{0,4}$ ]] && (( port <= 65535 )) || die 'MySQL 端口必须为 1 到 65535。'

state=$(mktemp -d "$PWD/invsync-mysql-XXXXXXXX")
container=$(basename -- "$state")
bootstrap="$state/bootstrap"
mkdir "$bootstrap"
printf '数据库工作目录 %s\n' "$state"
image=mysql:8.4
driver="$state/mysql-connector-j-9.6.0.jar"
driver_url='https://repo.maven.apache.org/maven2/com/mysql/mysql-connector-j/9.6.0/mysql-connector-j-9.6.0.jar'
driver_sha256='66df1d453789dc8cb759a7dc17f58646893bf28483f262328650f170472a6ead'
printf '下载并校验 MySQL Connector/J 9.6.0…\n'
curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' \
    --connect-timeout 10 --max-time 120 --retry 2 --output "$driver" "$driver_url" ||
    die '下载 MySQL 驱动失败，请检查能否访问 Maven Central。'
printf '%s  %s\n' "$driver_sha256" "$driver" | sha256sum --check --status ||
    die 'MySQL 驱动 SHA-256 校验失败，初始化已停止。'

openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 3650 \
    -subj '/CN=InvSync MySQL CA' -addext 'basicConstraints=critical,CA:TRUE' \
    -addext 'keyUsage=critical,keyCertSign,cRLSign' \
    -keyout "$state/ca.key" -out "$bootstrap/ca.pem"
openssl req -new -newkey rsa:2048 -nodes -sha256 -subj '/CN=InvSync MySQL' \
    -keyout "$bootstrap/server-key.pem" -out "$state/server.csr"
cat > "$state/server.ext" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=$san,DNS:localhost
EOF
openssl x509 -req -sha256 -days 825 -in "$state/server.csr" -CA "$bootstrap/ca.pem" \
    -CAkey "$state/ca.key" -CAcreateserial -extfile "$state/server.ext" -out "$bootstrap/server-cert.pem"
openssl verify -CAfile "$bootstrap/ca.pem" -purpose sslserver "$bootstrap/server-cert.pem"
openssl rand -hex 24 > "$bootstrap/root-password"

listen=127.0.0.1
if [[ $bind != "$listen" ]]; then listen+=",$bind"; fi

# Use host networking on Linux so MySQL sees each game server's source IP.
# The mounted bootstrap files are readable only by the invoking user and container root.
docker run -d --name "$container" --restart unless-stopped --network host --user 0:0 \
    --mount "type=volume,source=$container-data,target=/var/lib/mysql" \
    --mount "type=bind,source=$bootstrap,target=/invsync-bootstrap,readonly" \
    --env MYSQL_ROOT_PASSWORD_FILE=/invsync-bootstrap/root-password \
    --env MYSQL_ROOT_HOST=localhost \
    --entrypoint /bin/sh "$image" -ec '
        install -d -m 755 /run/invsync-tls
        install -m 644 /invsync-bootstrap/ca.pem /invsync-bootstrap/server-cert.pem /run/invsync-tls/
        install -o mysql -g mysql -m 600 /invsync-bootstrap/server-key.pem /run/invsync-tls/server-key.pem
        umask 077
        { printf "[client]\npassword="; cat /invsync-bootstrap/root-password; } > /run/invsync-tls/admin.cnf
        umask 022
        exec /usr/local/bin/docker-entrypoint.sh mysqld "$@"
    ' invsync "--bind-address=$listen" "--port=$port" --mysqlx=OFF \
    --require-secure-transport=ON --ssl-ca=/run/invsync-tls/ca.pem \
    --ssl-cert=/run/invsync-tls/server-cert.pem --ssl-key=/run/invsync-tls/server-key.pem >/dev/null

printf '等待 MySQL 初始化（容器 %s）…\n' "$container"
ready=false
for ((attempt=0; attempt<90; attempt++)); do
    if result=$(docker exec --user 0:0 "$container" mysql \
        --defaults-extra-file=/run/invsync-tls/admin.cnf --protocol=TCP -h localhost -P "$port" \
        -uroot --ssl-mode=VERIFY_IDENTITY --ssl-ca=/run/invsync-tls/ca.pem --connect-timeout=2 \
        -Nse 'SELECT @@GLOBAL.require_secure_transport' 2>/dev/null) && [[ $result == 1 ]]; then
        ready=true
        break
    fi
    sleep 2
done
if [[ $ready != true ]]; then
    docker logs --tail 25 "$container" >&2
    die "MySQL 未就绪。容器和数据保存在 $state。"
fi

if ! config=$( { printf '%s\n' "$host" "$port" "$fabric" "$spigot" "$bootstrap/ca.pem"; cat "$bootstrap/root-password"; } |
    java -Djava.net.preferIPv4Stack=true "$script_dir/SetupMySql.java" --bootstrap-stdin "$driver" "$state"); then
    die "MySQL 已启动，但生成配置失败。容器 $container 和初始化文件保存在 $state。"
fi
printf 'MySQL 已启动（%s:%s），TLS 校验已开启。\n' "$host" "$port"
printf '停服后，把以下目录中的文件复制到对应服务器根目录，再启动游戏服。\n'
printf 'Fabric: %s/Fabric/\nSpigot: %s/Spigot/\n' "$config" "$config"
printf 'MySQL 管理密码、CA 私钥和初始化材料保存在 %s，请保留在数据库机器上。\n' "$state"