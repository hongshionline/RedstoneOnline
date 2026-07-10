import os
import shutil
import glob

project_dir = r"D:\redstone-mod\multiloader-multiversion-template"
output_dir = r"D:\redstone-mod\rsonline-1_0_1"

if os.path.exists(output_dir):
    shutil.rmtree(output_dir)
os.makedirs(output_dir, exist_ok=True)

for jar in glob.glob(os.path.join(project_dir, "versions", "*", "build", "libs", "redstoneonline-*.jar")):
    filename = os.path.basename(jar)

    # redstoneonline-1.0.0+1.20.1-fabric.jar → rsonline-1_20_1-fabric.jar
    parts = filename.split("+")
    if len(parts) < 2:
        continue
    rest = parts[1]
    version = rest.split("-")[0].replace(".", "_")
    loader = rest.split("-")[1].replace(".jar", "")

    new_name = f"rsonline-{version}-{loader}.jar"
    shutil.copy2(jar, os.path.join(output_dir, new_name))
    print(f"  {new_name}")

print(f"\n已保存到: {output_dir}")
print(f"共 {len(os.listdir(output_dir))} 个文件")
