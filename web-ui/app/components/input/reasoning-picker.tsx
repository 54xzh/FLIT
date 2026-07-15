import * as React from "react";

import { useMutation } from "@tanstack/react-query";
import { ChevronDown, Lightbulb, LightbulbCircle, LoaderCircle, Sparkles } from "lucide-react";
import { useTranslation } from "react-i18next";

import { useCurrentAssistant } from "~/hooks/use-current-assistant";
import { useCurrentModel } from "~/hooks/use-current-model";
import { usePickerPopover } from "~/hooks/use-picker-popover";
import { extractErrorMessage } from "~/lib/error";
import { cn } from "~/lib/utils";
import api from "~/services/api";
import type { ProviderModel, ReasoningLevel } from "~/types";
import { Button } from "~/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverHeader,
  PopoverTitle,
  PopoverTrigger,
} from "~/components/ui/popover";

import { PickerErrorAlert } from "./picker-error-alert";

const REASONING_LEVELS: ReasoningLevel[] = [
  "off",
  "auto",
  "low",
  "medium",
  "high",
  "xhigh",
  "max",
];

export interface ReasoningPickerButtonProps {
  disabled?: boolean;
  className?: string;
}

function isReasoningModel(model: ProviderModel | null): boolean {
  if (!model) {
    return false;
  }

  return (model.abilities ?? []).includes("REASONING");
}

export function ReasoningPickerButton({ disabled = false, className }: ReasoningPickerButtonProps) {
  const { t } = useTranslation("input");
  const { settings, currentAssistant } = useCurrentAssistant();
  const { currentModel } = useCurrentModel();

  const canUse = Boolean(settings && currentAssistant && !disabled);
  const canReasoning = isReasoningModel(currentModel);
  const { open, error, setError, popoverProps } = usePickerPopover(canUse);

  const currentLevel: ReasoningLevel = currentAssistant?.reasoningLevel ?? "auto";

  React.useEffect(() => {
    if (!canUse || !canReasoning) {
      popoverProps.onOpenChange(false);
    }
  }, [canReasoning, canUse]);

  const updateReasoningLevelMutation = useMutation({
    mutationFn: ({
      assistantId,
      reasoningLevel,
    }: {
      assistantId: string;
      reasoningLevel: ReasoningLevel;
    }) =>
      api.post<{ status: string }>("settings/assistant/reasoning-level", {
        assistantId,
        reasoningLevel,
      }),
    onError: (updateError) => {
      setError(extractErrorMessage(updateError, t("reasoning.update_failed")));
    },
    onSuccess: () => setError(null),
  });

  const loading = updateReasoningLevelMutation.isPending;

  if (!canReasoning) {
    return null;
  }

  return (
    <Popover {...popoverProps}>
      <PopoverTrigger asChild>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={!canUse || loading}
          className={cn(
            "h-9 rounded-full border border-border/70 bg-muted/70 px-3 text-sm font-normal text-foreground shadow-none hover:bg-accent hover:text-accent-foreground",
            className,
          )}
        >
          <span>{t(`reasoning.presets.${currentLevel}.label`)}</span>
          <span className="hidden sm:block">
            {loading ? (
              <LoaderCircle className="size-3.5 animate-spin" />
            ) : (
              <ChevronDown className="size-3.5" />
            )}
          </span>
        </Button>
      </PopoverTrigger>

      <PopoverContent align="end" className="w-[min(92vw,24rem)] gap-0 p-0">
        <PopoverHeader className="px-6 pt-4 pb-2">
          <PopoverTitle>{t("reasoning.title")}</PopoverTitle>
          <PopoverDescription>{t("reasoning.description")}</PopoverDescription>
        </PopoverHeader>

        <div className="max-h-[70svh] space-y-4 overflow-y-auto px-4 py-4">
          <PickerErrorAlert error={error} />

          <div className="grid grid-cols-3 gap-2 rounded-[var(--radius-card)] border border-border/70 bg-muted/35 p-2">
            {REASONING_LEVELS.map((level) => {
              const selected = level === currentLevel;
              const switching =
                updateReasoningLevelMutation.isPending &&
                updateReasoningLevelMutation.variables?.reasoningLevel === level;

              return (
                <Button
                  key={level}
                  type="button"
                  size="sm"
                  variant={selected ? "default" : "outline"}
                  className={cn(
                    "h-8 w-full justify-start rounded-full px-2 text-xs",
                    !selected && "bg-background hover:bg-accent",
                    selected && "shadow-none",
                  )}
                  disabled={disabled || loading}
                  onClick={() => {
                    if (!currentAssistant) return;
                    updateReasoningLevelMutation.mutate({
                      assistantId: currentAssistant.id,
                      reasoningLevel: level,
                    });
                  }}
                >
                  {level === "off" ? (
                    <LightbulbCircle className="size-3.5" />
                  ) : level === "auto" ? (
                    <Sparkles className="size-3.5" />
                  ) : (
                    <Lightbulb className="size-3.5" />
                  )}
                  <span className="truncate">{t(`reasoning.presets.${level}.label`)}</span>
                  <span className="ml-auto flex size-3.5 items-center justify-center">
                    {switching ? <LoaderCircle className="size-3.5 animate-spin" /> : null}
                  </span>
                </Button>
              );
            })}
          </div>

          <div className="text-muted-foreground h-4 truncate px-1 text-xs">
            {t(`reasoning.presets.${currentLevel}.description`)}
          </div>
        </div>
      </PopoverContent>
    </Popover>
  );
}