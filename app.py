from flask import Flask, render_template, request, jsonify
import requests, random, time, threading

app = Flask(__name__)

# ग्लोबल स्टेट
state = {
    "country": "US",
    "interval": 5,
    "current_ip": None,
    "current_proxy": None,
    "running": False,
    "history": []
}

def get_proxy_list(country_code):
    url = f"https://cdn.jsdelivr.net/gh/proxyscrape/free-proxy-list@main/proxies/countries/{country_code.lower()}/data.txt"
    try:
        r = requests.get(url, timeout=10)
        if r.status_code == 200:
            return [p.strip() for p in r.text.strip().split("\n") if ":" in p]
    except:
        pass
    return []

def test_proxy(proxy):
    try:
        r = requests.get("http://httpbin.org/ip",
                         proxies={"http": f"http://{proxy}", "https": f"http://{proxy}"},
                         timeout=5)
        if r.status_code == 200:
            return r.json()["origin"]
    except:
        pass
    return None

def ip_changer_loop():
    while state["running"]:
        proxies = get_proxy_list(state["country"])
        random.shuffle(proxies)
        for p in proxies:
            if not state["running"]:
                break
            ip = test_proxy(p)
            if ip:
                state["current_ip"] = ip
                state["current_proxy"] = p
                state["history"].insert(0, {"ip": ip, "proxy": p, "time": time.strftime("%H:%M:%S")})
                state["history"] = state["history"][:10]
                break
            time.sleep(1)
        time.sleep(state["interval"])

@app.route("/")
def index():
    return render_template("index.html")

@app.route("/api/start", methods=["POST"])
def start():
    data = request.json
    state["country"] = data.get("country", "US")
    state["interval"] = int(data.get("interval", 5))
    if not state["running"]:
        state["running"] = True
        threading.Thread(target=ip_changer_loop, daemon=True).start()
    return jsonify({"status": "started", "state": state})

@app.route("/api/stop", methods=["POST"])
def stop():
    state["running"] = False
    return jsonify({"status": "stopped"})

@app.route("/api/status")
def status():
    return jsonify(state)

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
