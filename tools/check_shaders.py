#!/usr/bin/env python3
"""Check that each shader and the Kotlin that drives it agree about uniforms.

This is the one shader mistake that cannot be seen by reading either file alone,
and it fails hard: Media3's GlProgram collects the *active* uniforms when the
program links, and setFloatsUniform on a name it did not find throws. A uniform
that the GLSL never declares - a rename on one side, a typo, a parameter added to
the Kotlin and forgotten in the shader - is a crash on the first frame, or a
preview that is simply black with the exception swallowed by the runCatching
around setVideoEffects.

The other direction is quieter and worse: a uniform declared in the shader that
nothing ever sets reads as zero, so the effect is subtly wrong rather than
missing, and it looks like a tuning problem for as long as you are willing to
believe it.

Also checks the obvious structural things - balanced braces, a main(), a
precision qualifier - and that a vec3 uniform is handed three floats.

Run with no arguments from the repo root.
"""

import pathlib
import re
import sys

ASSETS = pathlib.Path("app/src/main/assets")
SOURCE = pathlib.Path("app/src/main/java")

UNIFORM = re.compile(r"^\s*uniform\s+(\w+)\s+(\w+)\s*;", re.MULTILINE)
ATTRIBUTE = re.compile(r"^\s*attribute\s+(\w+)\s+(\w+)\s*;", re.MULTILINE)
SET_FLOATS = re.compile(r'setFloatsUniform\(\s*"(\w+)"\s*,\s*([^\n]*)')
SET_FLOAT = re.compile(r'setFloatUniform\(\s*"(\w+)"')
SET_SAMPLER = re.compile(r'setSamplerTexIdUniform\(\s*"(\w+)"')
SET_ATTRIBUTE = re.compile(r'setBufferAttribute\(\s*"(\w+)"')
FRAGMENT_PATH = re.compile(r'FRAGMENT_SHADER_PATH\s*=\s*"([^"]+)"')
VERTEX_PATH = re.compile(r'VERTEX_SHADER_PATH\s*=\s*"([^"]+)"')

# How many floats each GLSL type wants. Samplers are set by their own call.
ARITY = {"float": 1, "vec2": 2, "vec3": 3, "vec4": 4, "mat3": 9, "mat4": 16}


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", "", text)


def literal_arity(argument: str) -> int | None:
    """How many floats a floatArrayOf(...) call passes, when it is spelled out."""
    match = re.match(r"\s*floatArrayOf\(([^)]*)\)", argument)
    if not match:
        return None
    inner = match.group(1).strip()
    if not inner:
        return 0
    # Commas inside a nested call would miscount, so only a flat list is trusted.
    if "(" in inner:
        return None
    return len(inner.split(","))


def main() -> int:
    problems: list[str] = []
    shaders = sorted(ASSETS.glob("*.glsl"))
    if not shaders:
        print("no shaders found")
        return 0

    kotlin = sorted(SOURCE.rglob("*.kt"))
    bodies = {path: path.read_text() for path in kotlin}

    used_by = {}
    for path, body in bodies.items():
        for match in FRAGMENT_PATH.finditer(body):
            used_by.setdefault(match.group(1), []).append(path)
        for match in VERTEX_PATH.finditer(body):
            used_by.setdefault(match.group(1), []).append(path)

    for shader in shaders:
        raw = shader.read_text()
        source = strip_comments(raw)

        if source.count("{") != source.count("}"):
            problems.append(f"{shader.name}: {source.count('{')} open braces, {source.count('}')} close")
        if "void main(" not in source:
            problems.append(f"{shader.name}: no main()")
        if "precision" not in source and "vertex" not in shader.name:
            problems.append(f"{shader.name}: fragment shader with no precision qualifier")

        declared = {name: kind for kind, name in UNIFORM.findall(source)}
        attributes = {name for _, name in ATTRIBUTE.findall(source)}

        drivers = used_by.get(shader.name, [])
        if not drivers:
            problems.append(f"{shader.name}: nothing references it")
            continue

        for driver in drivers:
            body = bodies[driver]
            # A vertex shader is shared, so only judge it on what it declares.
            if "vertex" in shader.name:
                for name in attributes:
                    if name not in SET_ATTRIBUTE.findall(body):
                        problems.append(f"{driver.name}: never sets attribute '{name}'")
                continue

            set_names = set(SET_FLOAT.findall(body)) | set(SET_SAMPLER.findall(body))
            for name, argument in SET_FLOATS.findall(body):
                set_names.add(name)
                kind = declared.get(name)
                if kind is None:
                    continue  # reported below
                wanted = ARITY.get(kind)
                given = literal_arity(argument)
                if wanted is not None and given is not None and wanted != given:
                    problems.append(
                        f"{driver.name}: '{name}' is {kind} in {shader.name} "
                        f"but is handed {given} float(s)"
                    )

            for name in sorted(set_names):
                if name not in declared:
                    problems.append(
                        f"{driver.name}: sets '{name}', which {shader.name} does not declare "
                        f"— GlProgram throws on this"
                    )
            for name in sorted(declared):
                if name not in set_names:
                    problems.append(
                        f"{shader.name}: declares '{name}', which {driver.name} never sets "
                        f"— it reads as zero"
                    )

    if problems:
        for problem in problems:
            print(problem)
        return 1
    print(f"shaders and their uniforms agree ({len(shaders)} shaders)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
