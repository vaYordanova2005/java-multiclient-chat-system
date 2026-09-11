// Ported from legacy/AvatarCatalog.java's JavaFX shape primitives to SVG.
// Coordinates use the same r-relative offsets as the Java version, with
// r fixed at 50 (viewBox is always -50..50) and scaled via width/height.

interface AvatarDef {
  id: string;
  bgColor: string;
}

export const AVATAR_CATALOG: AvatarDef[] = [
  { id: 'avatar_sun', bgColor: '#FCDCE1' },
  { id: 'avatar_cat', bgColor: '#C8CEEE' },
  { id: 'avatar_star', bgColor: '#D8BEE5' },
  { id: 'avatar_cloud', bgColor: '#E8DAF0' },
  { id: 'avatar_fox', bgColor: '#A7ABDE' },
];

const r = 50;

function SunFace() {
  return (
    <g>
      <circle cx={-r * 0.28} cy={-r * 0.1} r={r * 0.09} fill="#3d3458" />
      <circle cx={r * 0.28} cy={-r * 0.1} r={r * 0.09} fill="#3d3458" />
      <path
        d={`M ${-r * 0.3} ${r * 0.12} A ${r * 0.32} ${r * 0.22} 0 0 0 ${r * 0.3} ${r * 0.12}`}
        fill="none"
        stroke="#3d3458"
        strokeWidth={Math.max(1.5, r * 0.06)}
        strokeLinecap="round"
      />
    </g>
  );
}

function CatFace() {
  return (
    <g>
      <polygon points={`${-r * 0.5},${-r * 0.55} ${-r * 0.25},${-r * 0.95} ${-r * 0.05},${-r * 0.55}`} fill="#9FA6D6" />
      <polygon points={`${r * 0.5},${-r * 0.55} ${r * 0.25},${-r * 0.95} ${r * 0.05},${-r * 0.55}`} fill="#9FA6D6" />
      <circle cx={-r * 0.25} cy={-r * 0.05} r={r * 0.08} fill="#3d3458" />
      <circle cx={r * 0.25} cy={-r * 0.05} r={r * 0.08} fill="#3d3458" />
      <polygon points={`${-r * 0.06},${r * 0.15} ${r * 0.06},${r * 0.15} 0,${r * 0.25}`} fill="#3d3458" />
    </g>
  );
}

function StarFace() {
  const outer = r * 0.6;
  const inner = r * 0.28;
  const pts: string[] = [];
  for (let i = 0; i < 10; i++) {
    const angle = Math.PI / 2 + (i * Math.PI) / 5;
    const radius = i % 2 === 0 ? outer : inner;
    pts.push(`${radius * Math.cos(angle)},${-radius * Math.sin(angle)}`);
  }
  return <polygon points={pts.join(' ')} fill="#FFFFFF" opacity={0.9} />;
}

function CloudFace() {
  return (
    <g fill="#FFFFFF" opacity={0.95}>
      <ellipse cx={-r * 0.35} cy={-r * 0.05} rx={r * 0.3} ry={r * 0.25} />
      <ellipse cx={r * 0.35} cy={-r * 0.05} rx={r * 0.3} ry={r * 0.25} />
      <ellipse cx={0} cy={r * 0.05} rx={r * 0.55} ry={r * 0.32} />
    </g>
  );
}

function FoxFace() {
  return (
    <g>
      <polygon points={`${-r * 0.45},${-r * 0.5} ${-r * 0.55},${-r * 0.95} ${-r * 0.15},${-r * 0.6}`} fill="#F7C9A0" />
      <polygon points={`${r * 0.45},${-r * 0.5} ${r * 0.55},${-r * 0.95} ${r * 0.15},${-r * 0.6}`} fill="#F7C9A0" />
      <polygon
        points={`${-r * 0.18},${r * 0.12} ${r * 0.18},${r * 0.12} 0,${r * 0.4}`}
        fill="#FFFFFF"
        opacity={0.85}
      />
      <circle cx={-r * 0.22} cy={-r * 0.05} r={r * 0.08} fill="#3d3458" />
      <circle cx={r * 0.22} cy={-r * 0.05} r={r * 0.08} fill="#3d3458" />
      <circle cx={0} cy={r * 0.32} r={r * 0.06} fill="#3d3458" />
    </g>
  );
}

const FACES: Record<string, () => React.ReactElement> = {
  avatar_sun: SunFace,
  avatar_cat: CatFace,
  avatar_star: StarFace,
  avatar_cloud: CloudFace,
  avatar_fox: FoxFace,
};

interface AvatarProps {
  avatarId?: string | null;
  displayName: string;
  size: number;
  fallbackColor?: string;
}

export default function Avatar({ avatarId, displayName, size, fallbackColor = '#808080' }: AvatarProps) {
  const def = avatarId ? AVATAR_CATALOG.find((a) => a.id === avatarId) : undefined;
  const Face = def ? FACES[def.id] : undefined;

  if (def && Face) {
    return (
      <svg width={size} height={size} viewBox={`${-r} ${-r} ${r * 2} ${r * 2}`} style={{ flexShrink: 0 }}>
        <circle cx={0} cy={0} r={r} fill={def.bgColor} />
        <Face />
      </svg>
    );
  }

  const initial = displayName ? displayName[0]!.toUpperCase() : '?';
  return (
    <div
      style={{
        width: size,
        height: size,
        borderRadius: '50%',
        background: fallbackColor,
        color: '#fff',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontSize: Math.max(9, size * 0.5),
        flexShrink: 0,
        fontWeight: 600,
      }}
    >
      {initial}
    </div>
  );
}
