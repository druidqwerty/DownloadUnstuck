"""Per-item Steam timings from a PZ coop-console log (vanilla Workshop loop)."""
import re
import sys

ts_re = re.compile(r"st:([^>]+)>")
req, first_dl, done = {}, {}, {}
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    m = ts_re.search(line)
    if not m:
        continue
    t = int(re.sub(r"\D", "", m.group(1)) or 0)
    if "CheckItemState -> DownloadPending ID=" in line:
        req[line.rsplit("ID=", 1)[1].strip()] = t
    elif "|Downloading" in line and "ID=" in line:
        first_dl.setdefault(line.rsplit("ID=", 1)[1].strip(), t)
    elif "onItemDownloaded itemID=" in line:
        done[line.split("itemID=")[1].split()[0]] = t


def q(a, p):
    return a[min(len(a) - 1, int(p * len(a)))]


ov = sorted(first_dl[i] - req[i] for i in req if i in first_dl)
nodl = [i for i in req if i not in first_dl and i in done]
tot = sorted(done[i] - req[i] for i in req if i in done)
print("items requested:", len(req), "| showed Downloading:", len(ov), "| finished without Downloading:", len(nodl))
if ov:
    print("request -> Downloading ms: median %d p90 %d p99 %d max %d" % (q(ov, .5), q(ov, .9), q(ov, .99), ov[-1]))
if tot:
    print("request -> done ms: median %d p90 %d max %d, sum %d s" % (q(tot, .5), q(tot, .9), tot[-1], sum(tot) // 1000))
if nodl:
    print("finished without Downloading, request -> done ms:", sorted(done[i] - req[i] for i in nodl)[:15])
