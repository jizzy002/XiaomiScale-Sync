const variants = {
  dark: "bg-surface-dark",
  darker: "bg-surface-darker",
} as const;

export function DarkCard({
  children,
  variant = "dark",
}: {
  children: React.ReactNode;
  variant?: keyof typeof variants;
}) {
  return (
    <div className={`rounded-xl p-5 text-text-on-dark ${variants[variant]}`}>
      {children}
    </div>
  );
}
